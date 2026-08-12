package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.rankguess.RankGuessGame;
import xyz.zcraft.seira.rankguess.RankGuessGameService;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class RankGuessCommandHandler {
    private static final String USAGE = "用法：/rg start | /rg #Rank | /rg end";
    private static final Pattern RANK_PATTERN = Pattern.compile("^#(\\d+)[wk]?$");
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final RankGuessGameService games;
    private final Predicate<String> adminAuthorizer;

    public RankGuessCommandHandler(
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            RankGuessGameService games,
            Predicate<String> adminAuthorizer
    ) {
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.games = games;
        this.adminAuthorizer = adminAuthorizer;
    }

    private static Long parseRank(String argument) {
        final Matcher matcher = RANK_PATTERN.matcher(argument);
        if (!matcher.matches()) {
            return null;
        }
        try {
            long base = Long.parseLong(matcher.group(1));
            long multiplier = 1;

            if (argument.endsWith("w")) {
                multiplier = 10000;
            } else if (argument.endsWith("k")) {
                multiplier = 1000;
            }
            return base * multiplier;
        } catch (NumberFormatException _) {
            return null;
        }
    }

    public void handleRankGuess(Context ctx) {
        if (!ctx.inGroup()) {
            ctx.sendReply(PendingMessage.ofString("/rg 仅支持群聊使用。"));
            return;
        }
        if (ctx.argumentCount() != 1) {
            ctx.sendReply(PendingMessage.ofString(USAGE));
            return;
        }

        String argument = ctx.argument(0);
        if ("start".equalsIgnoreCase(argument)) {
            start(ctx);
            return;
        }
        if ("end".equalsIgnoreCase(argument)) {
            end(ctx, true);
            return;
        }

        Long rank = parseRank(argument);
        if (rank == null) {
            ctx.sendReply(PendingMessage.ofString(USAGE));
            return;
        }
        guess(ctx, rank);
    }

    private void start(Context ctx) {
        RankGuessGameService.Reservation reservation = games.reserve(ctx.groupId(), ctx.senderUserId());
        if (reservation == null) {
            ctx.sendReply(PendingMessage.ofString("本群已有一轮 Rank Guess 正在进行。"));
            return;
        }

        AtomicBoolean activated = new AtomicBoolean();
        taskCoordinator.runApiRequest(
                ctx,
                "Rank Guess Render",
                () -> {
                    final PendingMessage message = PendingMessage.ofMarkdownRaw(at(ctx) + "正在选定随机成绩...");
                    final boolean activeMessageEnabled = ctx.sendMessage(message);
                    if (!activeMessageEnabled) {
                        ctx.sendReply(message);
                    }

                    var randomScore = APIHelper.getRandomScore();
                    RankGuessGameService.Round round = RankGuessGameService.Round.from(randomScore);

                    String content = at(ctx) + "随机用户与成绩已选定，正在渲染回放片段...";

                    if (!activeMessageEnabled) {
                        content += "\n\n> 提示: 由于缺少主动消息权限，阶段提示已禁用。权限配置请见[这里](https://docs.seira.top/overview/use.html#extra-permission)。";
                    }

                    ctx.sendReply(PendingMessage.ofMarkdownRaw(content));

                    var renderTask = APIHelper.createObscuredReplayRenderTask(
                            round.scoreId(), taskCoordinator.createVideoUploadRequest(ctx)
                    );

                    var replay = taskCoordinator.waitForReplay(renderTask);
                    if (replay == null) {
                        ctx.sendReply(PendingMessage.ofMarkdownRaw("由于回放渲染失败，本轮游戏已取消~"));
                        return;
                    }

                    boolean videoSent = ctx.sendReply(taskCoordinator.replayVideoMessage(replay));
                    if (!videoSent) {
                        taskCoordinator.removeReplayResult(renderTask.taskId());
                        ctx.sendReply(PendingMessage.ofMarkdownRaw("由于回放发送失败，本轮游戏已取消~"));
                        return;
                    }

                    taskCoordinator.removeReplayResult(renderTask.taskId());
                    var game = games.activate(reservation, round);

                    if (game == null) {
                        ctx.sendReply(PendingMessage.ofMarkdownRaw("无法开始游戏，请稍后再试喵"));
                        return;
                    }

                    activated.set(true);

                    StringBuilder result = new StringBuilder("回放渲染完成，游戏已开始！请在群内发送 `/rg #Rank` 猜测排名~");

                    var hints = RankGuessGameService.prepareHints(round.getNormalHints());

                    if (!activeMessageEnabled) {
                        result.append("\n").append("> 提示: ");
                        for (RankGuessGame.Hint s : hints) {
                            result.append("\n").append("> ").append(s.content());
                        }
                    } else {
                        result.append("\n").append("> 第一个提示将在 1 分钟后揭晓~");
                    }
                    boolean startMessageSent = ctx.sendReply(PendingMessage.ofMarkdownRaw(result.toString().trim()));

                    if (!activeMessageEnabled) {
                        if (startMessageSent) {
                            game.revealHints(hints);
                        }
                        return;
                    }

                    boolean firstHint = true;

                    while (!hints.isEmpty()) {
                        try {
                            //noinspection BusyWait
                            Thread.sleep((firstHint ? 60 : 30) * 1000);
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                            break;
                        }

                        firstHint = false;

                        if (game.isEnded()) {
                            return;
                        }

                        final var hint = hints.removeFirst();

                        String hintContent = "> 提示: " + hint.content();

                        if (!hints.isEmpty()) {
                            hintContent += "\n" + "> 下一个提示将在 30 秒后揭晓~";
                        } else {
                            hintContent += "\n" + "> 所有提示已经揭晓!游戏将在 1 分钟后自动结束~";
                        }

                        if (ctx.sendMessage(PendingMessage.ofMarkdownRaw(hintContent))) {
                            game.revealHint(hint);
                        }
                    }

                    try {
                        Thread.sleep(60 * 1000);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }

                    end(ctx, false);
                }
        );
        if (!activated.get()) {
            games.cancel(reservation);
        }
    }

    private void guess(Context ctx, long rank) {
        RankGuessGameService.GuessResponse response = games.guess(ctx.groupId(), ctx.senderUserId(), rank);
        final RankGuessGameService.GuessResult result = response.guessResult();
        PendingMessage message = switch (result.status()) {
            case NO_GAME -> PendingMessage.ofString("本群当前没有进行中的 Rank Guess 喵");
            case STARTING -> PendingMessage.ofString("回放仍在渲染，请等待视频发送后再猜测喵");
            case UPDATED, RECORDED -> PendingMessage.ofMarkdownRaw(
                    at(ctx)
                            + "已" + (result.status() == RankGuessGameService.GuessStatus.UPDATED ? "更新" : "记录") + "你的猜测："
                            + "`#" + String.format(Locale.US, "%,d", rank) + "`"
                            + " " + result.multiplierString()
            );
        };
        ctx.sendReply(message);
        if (response.message() != null && !response.message().isBlank()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(response.message()));
        }
    }

    private void end(Context ctx, boolean fromCommand) {
        RankGuessGameService.EndResult result = games.end(
                ctx.groupId(), ctx.senderUserId(), adminAuthorizer.test(ctx.senderUserId()), !fromCommand
        );
        PendingMessage message = switch (result.status()) {
            case NO_GAME -> PendingMessage.ofString("本群当前没有进行中的 Rank Guess 喵");
            case STARTING -> PendingMessage.ofString("高光仍在渲染，请等待视频发送后再结束游戏喵");
            case FORBIDDEN -> PendingMessage.ofString(
                    "开始猜测后的3分钟内，仅发起者和机器人管理员可以结束游戏喵"
            );
            case FINISHED -> replyFactory.rankGuessResultMessage(result.round());
        };

        if (fromCommand) {
            ctx.sendReply(message);
        } else {
            ctx.sendMessage(message);
        }
    }
}

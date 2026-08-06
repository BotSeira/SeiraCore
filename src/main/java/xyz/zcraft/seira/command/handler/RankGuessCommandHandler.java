package xyz.zcraft.seira.command.handler;

import org.jetbrains.annotations.NotNull;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.game.RankGuessGameService;

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
            end(ctx);
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
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "正在选定随机成绩..."));
                    var randomScore = APIHelper.getRandomScore();
                    RankGuessGameService.Round round = RankGuessGameService.Round.from(randomScore);
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(
                            at(ctx) + "随机用户与成绩已选定，正在渲染回放片段..."
                    ));

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
                    games.activate(reservation, round);
                    activated.set(true);
                    String range = getRange(round.actualRank());
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(
                            "回放渲染完成，游戏已开始！请在群内发送 `/rg #Rank` 猜测排名~\n"
                                    + "> 提示: 这是一名 `" + range + "` 玩家的`BP" + round.bestIndex() + "`~"
                    ));
                }
        );
        if (!activated.get()) {
            games.cancel(reservation);
        }
    }

    @NotNull
    private String getRange(long l) {
        String range;

        if (l <= 10_000) {
            range = "#1 - #10k";
        } else if (l <= 50_000) {
            range = "#10k - #50k";
        } else if (l <= 200_000) {
            range = "#50k - #200k";
        } else if (l <= 500_000) {
            range = "#200k - #500k";
        } else {
            range = ">#500k";
        }

        return range;
    }

    private void guess(Context ctx, long rank) {
        RankGuessGameService.GuessResult result = games.guess(ctx.groupId(), ctx.senderUserId(), rank);
        PendingMessage message = switch (result.status()) {
            case NO_GAME -> PendingMessage.ofString("本群当前没有进行中的 Rank Guess 喵");
            case STARTING -> PendingMessage.ofString("回放仍在渲染，请等待视频发送后再猜测喵");
            case UPDATED, RECORDED -> PendingMessage.ofMarkdownRaw(
                    at(ctx)
                            + "已" + (result.status() == RankGuessGameService.GuessStatus.UPDATED ? "更新" : "记录") + "你的猜测："
                            + "`#" + String.format(Locale.US, "%,d", rank) + "`"
                            + " " + result.getMultipliersString()
            );
        };
        ctx.sendReply(message);
    }

    private void end(Context ctx) {
        RankGuessGameService.EndResult result = games.end(
                ctx.groupId(), ctx.senderUserId(), adminAuthorizer.test(ctx.senderUserId())
        );
        PendingMessage message = switch (result.status()) {
            case NO_GAME -> PendingMessage.ofString("本群当前没有进行中的 Rank Guess 喵");
            case STARTING -> PendingMessage.ofString("高光仍在渲染，请等待视频发送后再结束游戏喵");
            case FORBIDDEN -> PendingMessage.ofString(
                    "开始猜测后的3分钟内，仅发起者和机器人管理员可以结束游戏喵"
            );
            case FINISHED -> replyFactory.rankGuessResultMessage(result.round());
        };
        ctx.sendReply(message);
    }
}

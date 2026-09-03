package xyz.zcraft.seira.command.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.RandomScore;
import xyz.zcraft.seira.bot.data.MessageReference;
import xyz.zcraft.seira.data.SendResult;
import xyz.zcraft.seira.db.UserDataStore;
import xyz.zcraft.seira.db.RankGuessRecordStore;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.UserRefResolution;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.UserRef;
import xyz.zcraft.seira.rankguess.RankGuessGame;
import xyz.zcraft.seira.rankguess.RankGuessGameService;
import xyz.zcraft.seira.rankguess.WishResult;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class RankGuessCommandHandler {
    private static final Logger LOG = LogManager.getLogger(RankGuessCommandHandler.class);
    private static final String USAGE = "用法：/rg start | /rg group | /rg #Rank | /rg end | /rg wish | /rg stats [all]";
    private static final Pattern RANK_PATTERN = Pattern.compile("^#?(\\d+)[wk]?$");
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final RankGuessGameService games;
    private final Resolver resolver;
    private final Predicate<String> adminAuthorizer;

    public RankGuessCommandHandler(
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            RankGuessGameService games,
            Resolver resolver,
            Predicate<String> adminAuthorizer
    ) {
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.games = games;
        this.resolver = resolver;
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

            return Math.multiplyExact(base, multiplier);
        } catch (NumberFormatException | ArithmeticException _) {
            return null;
        }
    }

    public void handleRankGuess(Context ctx) {
        if (!ctx.inGroup()) {
            ctx.sendReply(PendingMessage.ofString("/rg 仅支持群聊使用。"));
            return;
        }

        if (ctx.argumentCount() == 0) {
            currentStatus(ctx);
            return;
        }

        String argument = ctx.argument(0);
        if ("stats".equalsIgnoreCase(argument)) {
            if (ctx.argumentCount() == 1) {
                statistics(ctx, false);
            } else if (ctx.argumentCount() == 2 && "all".equalsIgnoreCase(ctx.argument(1))) {
                statistics(ctx, true);
            } else {
                ctx.sendReply(PendingMessage.ofString(USAGE));
            }
            return;
        }
        if ("start".equalsIgnoreCase(argument)) {
            if (ctx.argumentCount() == 2) {
                final String arg = ctx.argument(1);
                if ("group".equalsIgnoreCase(arg) || "g".equalsIgnoreCase(arg)) {
                    start(ctx, true);
                    return;
                }
            } else if (ctx.argumentCount() == 1) {
                start(ctx, false);
                return;
            }

            ctx.sendReply(PendingMessage.ofString(USAGE));
            return;
        }
        if ("group".equalsIgnoreCase(argument)) {
            if (ctx.argumentCount() != 1) {
                ctx.sendReply(PendingMessage.ofString(USAGE));
                return;
            }

            start(ctx, true);
            return;
        }
        if ("end".equalsIgnoreCase(argument)) {
            if (ctx.argumentCount() != 1) {
                ctx.sendReply(PendingMessage.ofString(USAGE));
                return;
            }
            end(ctx, false);
            return;
        }

        if ("wish".equalsIgnoreCase(argument)) {
            if (ctx.argumentCount() != 1) {
                ctx.sendReply(PendingMessage.ofString(USAGE));
                return;
            }
            wish(ctx);
            return;
        }

        if ("weight".equalsIgnoreCase(argument)) {
            if (ctx.argumentCount() != 1) {
                ctx.sendReply(PendingMessage.ofString(USAGE));
                return;
            }
            weight(ctx);
            return;
        }

        Long rank;

        if (resolver.looksLikeMention(argument)) {
            final UserRefResolution userRefResolution = resolver.resolveUserRefArgument(argument);

            if (userRefResolution.errorMessage() != null) {
                ctx.sendReply(userRefResolution.errorMessage());
            }

            final UserRef userRef = userRefResolution.userRef();

            rank = APIHelper.getUserRank(userRef);
        } else {
            rank = parseRank(argument);
            if (rank == null) {
                ctx.sendReply(PendingMessage.ofString(USAGE));
                return;
            }
        }

        guess(ctx, rank);
    }

    private void currentStatus(Context ctx) {
        final RankGuessGameService.GameStatus status = games.getStatus(ctx.groupId());
        if (status == RankGuessGameService.GameStatus.NO_GAME) {
            ctx.sendReply(PendingMessage.ofString("目前本群没有进行中的猜 Rank 游戏喵！可以使用 /rg group 或 /rg start 开始游戏喵~"));
            return;
        }

        final MessageReference videoMessageRef = games.getVideoMessageRef(ctx.groupId());

        if (status == RankGuessGameService.GameStatus.STARTING || videoMessageRef == null) {
            ctx.sendReply(PendingMessage.ofString("游戏即将开始，稍等片刻喵~"));
            return;
        }

        String reply = "本群猜 Rank 正火热进行中🔥🔥🔥" +
                "\n目前已经有 " + games.getParticipantCount(ctx.groupId()) + " 个参与者~";

        ctx.sendReply(PendingMessage.ofString(reply).ref(games.getVideoMessageRef(ctx.groupId())));
    }

    private void weight(Context ctx) {
        StringBuilder reply = new StringBuilder();
        final String groupWeight = games.generateWeights(ctx.groupId()).toString();
        reply.append(at(ctx)).append("目前本群权重:\n```json\n").append(groupWeight).append("\n```\n");

        final Long boundUid = UserDataStore.findBoundUid(ctx.senderUserId());
        if (boundUid != null) {
            final String randomScoreWeight = APIHelper.getRandomScoreWeight(boundUid);

            reply.append("你的成绩权重:\n>").append(randomScoreWeight).append("\n");
        }


        ctx.sendReply(PendingMessage.ofMarkdownRaw(reply.toString().trim()));
    }

    private void statistics(Context ctx, boolean allGroups) {
        try {
            RankGuessRecordStore.Statistics statistics = RankGuessRecordStore.getStatistics(
                    ctx.senderUserId(),
                    allGroups ? null : ctx.groupId()
            );

            ctx.sendReply(replyFactory.rankGuessStatisticsMessage(ctx, statistics, allGroups, getRank(statistics)));
        } catch (RuntimeException e) {
            LOG.error("Failed to query rank guess statistics", e);
            ctx.sendReply(PendingMessage.ofString("战绩查询失败，请稍后重试喵。"));
        }
    }

    private String getRank(RankGuessRecordStore.Statistics statistics) {
        if (statistics.participation() < 3) {
            return "?";
        }

        double averageScoreRate = Math.clamp(
                statistics.averageScore() / 1000.0,
                0.0, 1.0
        );

        double rawRating =
                averageScoreRate * 0.50
                        + statistics.winRate() * 0.20
                        + statistics.topTwentyRate() * 0.30;

        double confidence = 1.0 - Math.exp(-statistics.participation() / 10.0);

        double rating = 0.50 * (1.0 - confidence) + rawRating * confidence;


        if (rating >= 0.90) return "SS";
        if (rating >= 0.82) return "S";
        if (rating >= 0.74) return "A";
        if (rating >= 0.64) return "B";
        if (rating >= 0.52) return "C";
        if (rating >= 0.40) return "D";
        return "F";
    }

    private void wish(Context ctx) {
        final Long boundUid = UserDataStore.findBoundUid(ctx.senderUserId());

        if (boundUid == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "由于未绑定，无法进行许愿喵~"));
            return;
        }

        final WishResult wish = games.wish(ctx.groupId(), boundUid);

        ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + switch (wish) {
            case SUCCESS -> "小星听到你的愿望啦！";
            case ALREADY_WISHED -> "已经许过愿了喵~";
            case RECENTLY_PICKED -> "最近已经被抽到过了喵~";
            case null -> "发生了一些不好的事情...";
        }));
    }

    private void start(Context ctx, boolean fromGroup) {
        RankGuessGameService.Reservation reservation = games.reserve(ctx.groupId(), ctx.senderUserId(), fromGroup);
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
                    final boolean activeMessageEnabled = ctx.sendMessage(message).success();
                    if (!activeMessageEnabled) {
                        ctx.sendReply(message);
                    }

                    RandomScore randomScore;

                    if (fromGroup) {
                        final List<Long> uids = UserDataStore.findBoundUidsByGroup(ctx.groupId());
                        if (uids.isEmpty()) {
                            ctx.sendReply(PendingMessage.ofMarkdownRaw("本群没有绑定的用户，无法开始游戏喵"));
                            return;
                        }
                        randomScore = APIHelper.getRandomScoreFromUsers(uids, games.generateWeights(ctx.groupId()));
                    } else {
                        randomScore = APIHelper.getRandomScore();
                    }

                    RankGuessGameService.Round round = RankGuessGameService.Round.from(randomScore);

                    String content = at(ctx);

                    if (fromGroup) {
                        content += "随机群友及其成绩已选定";
                    } else {
                        content += "随机用户与成绩已选定";
                    }

                    content += "，正在渲染回放片段...";

                    if (!activeMessageEnabled) {
                        content += "\n\n> 提示: 由于缺少主动消息权限，阶段提示与自动结束已禁用。稍后需要使用 `/rg end` 手动结束。权限配置请见[这里](https://docs.seira.top/overview/use.html#extra-permission)。";
                    }

                    ctx.sendReply(PendingMessage.ofMarkdownRaw(content));

                    var renderTask = APIHelper.createObscuredReplayRenderTask(
                            round.scoreId(), taskCoordinator.createVideoUploadRequest(ctx)
                    );

                    APIHelper.ReplayRenderResult replay = null;
                    try {
                        replay = taskCoordinator.waitForReplay(renderTask);
                    } catch (Exception e) {
                        LOG.error("Failed to render replay for rank guess", e);
                    }

                    if (replay == null) {
                        ctx.sendReply(PendingMessage.ofMarkdownRaw("由于回放渲染失败，本轮游戏已取消~"));
                        return;
                    }

                    final SendResult sendResult = ctx.sendReply(taskCoordinator.replayVideoMessage(replay));
                    boolean videoSent = sendResult.success();
                    if (!videoSent) {
                        taskCoordinator.removeReplayResult(renderTask.taskId());
                        ctx.sendReply(PendingMessage.ofMarkdownRaw("由于回放发送失败，本轮游戏已取消~"));
                        return;
                    }

                    taskCoordinator.removeReplayResult(renderTask.taskId());
                    var game = games.activate(reservation, round, MessageReference.of(sendResult.sentMessage()));

                    if (game == null) {
                        ctx.sendReply(PendingMessage.ofMarkdownRaw("无法开始游戏，请稍后再试喵"));
                        return;
                    }

                    activated.set(true);

                    StringBuilder result = new StringBuilder("回放渲染完成，游戏已开始！请在群内发送 `/rg #Rank` 猜测排名~");

                    if (fromGroup) {
                        result.append("\n").append("__Tip: 这是一位群友的成绩喵~__").append("\n");
                    }

                    var hints = RankGuessGameService.prepareHints(round.getNormalHints(), 4);

                    if (!activeMessageEnabled) {
                        result.append("\n").append("> 提示: ");
                        for (RankGuessGame.Hint s : hints) {
                            result.append("\n").append("> ").append(s.content());
                        }
                    } else {
                        result.append("\n").append("> 第一个提示将在 1 分钟后揭晓~");
                    }
                    boolean startMessageSent = ctx.sendReply(PendingMessage.ofMarkdownRaw(result.toString().trim())).success();

                    if (!activeMessageEnabled) {
                        if (startMessageSent) {
                            game.revealHints(hints);
                        }
                        return;
                    }

                    boolean firstHint = true;

                    StringBuilder hintString = new StringBuilder();

                    while (!hints.isEmpty() && !game.isEnded()) {
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

                        hintString.insert(0, "- " + hint.content() + "\n");

                        String hintContent = "__猜Rank提示：__\n" + hintString;

                        if (!hints.isEmpty()) {
                            hintContent += "\n" + "> 下一个提示将在 30 秒后揭晓~";
                        } else {
                            hintContent += "\n" + "> 所有提示已经揭晓啦！游戏将在 1 分钟后自动结束~";
                        }

                        if (ctx.sendMessage(PendingMessage.ofMarkdownRaw(hintContent)).success()) {
                            game.revealHint(hint);
                        }
                    }

                    try {
                        Thread.sleep(60 * 1000);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }

                    if (!game.isEnded()) {
                        end(ctx, true);
                    }
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
            case TOO_SOON -> PendingMessage.ofString("距离上次猜测不足20秒，无法修改猜测喵");
            case UPDATED, RECORDED -> PendingMessage.ofMarkdownRaw(
                    at(ctx)
                            + "已" + (result.status() == RankGuessGameService.GuessStatus.UPDATED ? "更新" : "记录") + "你的猜测："
                            + "`#" + String.format(Locale.US, "%,d", rank) + "`"
                            + " " + result.multiplierString() + "\n"
                            + "目前已经有 `" + result.guessCount() + "` 个猜测~"
            );
        };
        ctx.sendReply(message);
        if (response.message() != null && !response.message().isBlank()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(response.message()));
        }

        if (result.status() != RankGuessGameService.GuessStatus.UPDATED
                && result.status() != RankGuessGameService.GuessStatus.RECORDED) {
            return;
        }

        if (rank == response.game().getRound().actualRank()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw("看来已经有人知晓了答案喵！游戏将会自动结束~"));
            end(ctx, true);
        }
    }

    private void end(Context ctx, boolean force) {
        RankGuessGameService.EndResult result;
        try {
            result = games.end(ctx.groupId(), ctx.senderUserId(), adminAuthorizer.test(ctx.senderUserId()), force);
        } catch (RankGuessRecordStore.RecordSaveException e) {
            LOG.error("Failed to record rank guess round in group {}", ctx.groupId(), e);
            PendingMessage failure = PendingMessage.ofString("战绩保存失败，本轮尚未结算，请稍后使用 /rg end 重试喵。");
            if (!ctx.sendReply(failure).success()) ctx.sendMessage(failure);
            return;
        }

        PendingMessage message = switch (result.status()) {
            case NO_GAME -> PendingMessage.ofString("本群当前没有进行中的 Rank Guess 喵");
            case STARTING -> PendingMessage.ofString("高光仍在渲染，请等待视频发送后再结束游戏喵");
            case FORBIDDEN -> PendingMessage.ofString(
                    "开始猜测后的3分钟内，仅发起者和机器人管理员可以结束游戏喵"
            );
            case FINISHED -> replyFactory.rankGuessResultMessage(ctx, result.round(), result.recorded());
        };

        if (!ctx.sendReply(message).success()) {
            ctx.sendMessage(message);
        }
    }
}

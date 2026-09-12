package xyz.zcraft.seira.command.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.RandomScore;
import xyz.zcraft.seira.bot.data.MessageReference;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.ShortcutTarget;
import xyz.zcraft.seira.command.parse.UserRefResolution;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.SendResult;
import xyz.zcraft.seira.data.UserRef;
import xyz.zcraft.seira.db.RankGuessRecordStore;
import xyz.zcraft.seira.db.UserDataStore;
import xyz.zcraft.seira.rankguess.HintUtil;
import xyz.zcraft.seira.rankguess.RankGuessGame;
import xyz.zcraft.seira.rankguess.RankGuessGameService;
import xyz.zcraft.seira.rankguess.data.*;
import xyz.zcraft.seira.util.RandomReply;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;
import static xyz.zcraft.seira.command.reply.ReplyFactory.cmd;

public final class RankGuessCommandHandler {
    static final Logger LOG = LogManager.getLogger(RankGuessCommandHandler.class);
    static final Pattern RANGE_PATTERN = Pattern.compile("^(\\d+)-(\\d+)$");
    static final int MAX_LEADERBOARD_RANGE = 50;
    private static final String USAGE = "用法：/rg start|group|#Rank|end|wish|stats|lb";
    private static final Pattern RANK_PATTERN = Pattern.compile("^#?(\\d+)[wk]?$");
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final RankGuessGameService games;
    private final Resolver resolver;
    private final Predicate<String> adminAuthorizer;
    private final Pattern BP_PATTERN = Pattern.compile("^bp(\\d+)$");

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
        final Matcher matcher = RANK_PATTERN.matcher(argument.replace(",", ""));
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

    private LeaderboardRange parseLeaderboardRange(String raw) {
        final Matcher matcher = RANGE_PATTERN.matcher(raw);

        if (!matcher.matches()) {
            return null;
        }

        try {
            final int start = Integer.parseInt(matcher.group(1));
            final int end = Integer.parseInt(matcher.group(2));

            if (start < 1 || end < start) {
                return null;
            }

            if (end - start + 1 > MAX_LEADERBOARD_RANGE) {
                return null;
            }

            return new LeaderboardRange(start, end);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    public void handleRankGuess(Context ctx) {
        if (!ctx.inGroup()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "/rg 仅支持群聊使用。"));
            return;
        }

        if (ctx.argumentCount() == 0) {
            currentStatus(ctx);
            return;
        }

        String argument = ctx.argument(0);

        switch (argument.toLowerCase()) {
            case "stats" -> {
                if (ctx.argumentCount() == 1) {
                    statistics(ctx, false, null);
                } else if (ctx.argumentCount() == 2 && resolver.looksLikeMention(ctx.argument(1))) {
                    final String s = resolver.extractMentionedUserId(ctx.argument(1));
                    statistics(ctx, false, s);
                } else if (ctx.argumentCount() == 2 && "all".equalsIgnoreCase(ctx.argument(1))) {
                    statistics(ctx, true, null);
                } else {
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + USAGE));
                }
                return;
            }
            case "lb" -> {
                if (ctx.argumentCount() == 1) {
                    LeaderboardHandler.leaderboard(ctx, LeaderboardType.GROUP_SELF);
                    return;
                }

                if (ctx.argumentCount() == 2) {
                    final String arg = ctx.argument(1);

                    if ("all".equalsIgnoreCase(arg)) {
                        LeaderboardHandler.leaderboard(ctx, LeaderboardType.GROUP_FULL);
                        return;
                    }

                    if ("global".equalsIgnoreCase(arg)) {
                        LeaderboardHandler.leaderboard(ctx, LeaderboardType.GLOBAL_SELF);
                        return;
                    }

                    final LeaderboardRange range = parseLeaderboardRange(arg);

                    if (range != null) {
                        LeaderboardHandler.leaderboard(ctx, LeaderboardType.GROUP_RANGE, range.start(), range.end());
                        return;
                    }

                    usage(ctx);
                    return;
                }

                if (ctx.argumentCount() == 3 && "global".equalsIgnoreCase(ctx.argument(1))) {

                    final LeaderboardRange range = parseLeaderboardRange(ctx.argument(2));

                    if (range != null) {
                        LeaderboardHandler.leaderboard(ctx, LeaderboardType.GLOBAL_RANGE, range.start(), range.end());
                        return;
                    }
                }

                usage(ctx);
                return;
            }
            case "start" -> {
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

                usage(ctx);
                return;
            }
            case "group" -> {
                if (ctx.argumentCount() != 1) {
                    usage(ctx);
                    return;
                }

                start(ctx, true);
                return;
            }
            case "end" -> {
                if (ctx.argumentCount() != 1) {
                    usage(ctx);
                    return;
                }
                end(ctx, false);
                return;
            }
            case "wish" -> {
                if (ctx.argumentCount() == 1) {
                    wish(ctx);
                } else if (ctx.argumentCount() == 2) {
                    final Matcher matcher = BP_PATTERN.matcher(ctx.argument(1).toLowerCase());
                    if (!matcher.matches()) {
                        usage(ctx);
                        return;
                    }

                    final int i;
                    try {
                        i = Integer.parseInt(matcher.group(1));
                    } catch (NumberFormatException _) {
                        usage(ctx);
                        return;
                    }

                    if (i <= 0 || i > 200) {
                        usage(ctx);
                        return;
                    }

                    wishScore(ctx, i);
                } else {
                    usage(ctx);
                }
                return;
            }
            case "weight" -> {
                if (ctx.argumentCount() == 1) {
                    weight(ctx, false, null);
                } else if (ctx.argumentCount() == 2 && resolver.looksLikeMention(ctx.argument(1))) {
                    final String s = resolver.extractMentionedUserId(ctx.argument(1));
                    weight(ctx, false, s);
                } else if (ctx.argumentCount() == 2 && "all".equalsIgnoreCase(ctx.argument(1))) {
                    weight(ctx, true, null);
                } else {
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + USAGE));
                }
                return;
            }
        }

        Long rank;

        if (resolver.looksLikeMention(argument)) {
            final UserRefResolution userRefResolution = resolver.resolveUserRefArgument(argument);

            if (userRefResolution.errorMessage() != null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + userRefResolution.errorMessage()));
                return;
            }

            final UserRef userRef = userRefResolution.userRef();

            rank = APIHelper.getUserRank(userRef);
        } else {
            rank = parseRank(argument);
            if (rank == null) {
                usage(ctx);
                return;
            }
        }

        guess(ctx, rank);
    }

    private void usage(Context ctx) {
        ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + USAGE));
    }

    private void currentStatus(Context ctx) {
        final RankGuessGameService.GameStatus status = games.getStatus(ctx.groupId());
        if (status == RankGuessGameService.GameStatus.NO_GAME) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "目前本群没有进行中的猜 Rank 游戏喵！可以使用 "
                    + cmd("/rg group") + " 或 " + cmd("/rg start") + " 开始游戏喵~"));
            return;
        }

        final MessageReference videoMessageRef = games.getVideoMessageRef(ctx.groupId());

        if (status == RankGuessGameService.GameStatus.STARTING || videoMessageRef == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "游戏即将开始，稍等片刻喵~"));
            return;
        }

        String reply = "本群猜 Rank 正火热进行中🔥🔥🔥" +
                "\n目前已经有 " + games.getParticipantCount(ctx.groupId()) + " 个参与者~";

        ctx.sendReply(PendingMessage.ofString(reply).ref(games.getVideoMessageRef(ctx.groupId())));
    }

    private void weight(Context ctx, boolean all, String target) {
        final String effectiveTarget = target == null ? ctx.senderUserId() : target;
        final Long boundUid = UserDataStore.findBoundUid(effectiveTarget);

        final String ref = target == null ? "你" : "对方";

        if (boundUid == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "由于" + ref + "未绑定，无法查看权重喵~"));
            return;
        }

        ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "正在计算" + ref + "的权重喵~\n> Tip: 第一次计算可能耗时较长"));

        StringBuilder reply = new StringBuilder();

        final var probability = games.getProbabilityFor(ctx.groupId(), boundUid);
        final int totalPlayer = UserDataStore.findBoundUidsByGroup(ctx.groupId()).size();

        final String factors = String.join(",", probability.factors());

        reply.append(at(ctx)).append("目前%s在本群权重为 `%.2f` (%s)\n".formatted(ref, probability.weight(), factors.isBlank() ? "基础权重" : factors));
        reply.append("在本群 `%d` 名玩家中，%s被选中的概率为 `%.3f%%`\n".formatted(totalPlayer, ref, probability.chance() * 100));

        final String randomScoreWeight = APIHelper.getRandomScoreWeight(boundUid, games.generateWeights(ctx.groupId()), all);

        reply.append("%s的成绩当前抽选概率：\n>".formatted(ref)).append(randomScoreWeight).append("\n");

        ctx.sendReply(PendingMessage.ofMarkdownRaw(reply.toString().trim()));
    }

    private void statistics(Context ctx, boolean allGroups, String target) {
        final String effectiveTarget = target == null ? ctx.senderUserId() : target;
        final Long boundUid = UserDataStore.findBoundUid(effectiveTarget);
        final String ref = target == null ? "你" : "对方";
        if (boundUid == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "由于" + ref + "未绑定，无法查看战绩喵~"));
            return;
        }
        try {
            RankGuessRecordStore.Statistics.Personal statistics = RankGuessRecordStore.getPersonalStatistics(
                    effectiveTarget,
                    allGroups ? null : ctx.groupId(),
                    null,
                    Rank.STATS_MIN_PARTICIPANTS
            );

            RankGuessRecordStore.Statistics.Personal recentStatistics = RankGuessRecordStore.getRecentPersonalStatistics(
                    effectiveTarget,
                    allGroups ? null : ctx.groupId(),
                    null,
                    Rank.RECENT_GAME_LIMIT,
                    Rank.STATS_MIN_PARTICIPANTS
            );

            final Rank rank = Rank.from(recentStatistics, statistics);

            Long groupGameCount = RankGuessRecordStore.getGroupGameCount(ctx.groupId(), null);
            Long pickedTimes = RankGuessRecordStore.getPickedTimes(boundUid, ctx.groupId());
            RankGuessRecordStore.RankGuessed rankGuessed = RankGuessRecordStore.getAverageRankGuessed(boundUid, ctx.groupId());

            ctx.sendReply(replyFactory.rankGuessStatisticsMessage(
                    ctx, ref, statistics, recentStatistics, allGroups, rank, pickedTimes, groupGameCount, rankGuessed
            ));
        } catch (RuntimeException e) {
            LOG.error("Failed to query rank guess statistics", e);
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "战绩查询失败，请稍后重试喵。"));
        }
    }

    private void wish(Context ctx) {
        final Long boundUid = UserDataStore.findBoundUid(ctx.senderUserId());

        if (boundUid == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "由于未绑定，无法进行许愿喵~"));
            return;
        }

        final RankGuessGameService.WishResult wish = games.wish(ctx.groupId(), boundUid);

        ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + switch (wish) {
            case SUCCESS -> "小星听到你的愿望啦！";
            case ALREADY_WISHED -> "已经许过愿了喵~";
            case RECENTLY_PICKED -> "最近已经被抽到过了喵~";
            case null -> "发生了一些不好的事情...";
        }));
    }

    private void wishScore(Context ctx, int index) {
        final Long boundUid = UserDataStore.findBoundUid(ctx.senderUserId());

        if (boundUid == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "由于未绑定，无法进行许愿喵~"));
            return;
        }

        Long scoreId = null;

        try {
            scoreId = Long.parseLong(
                    APIHelper.lookupScoreId(new ShortcutTarget(
                                    null, new UserRef.ByUid(boundUid), "bp", (long) index, null)
                            , List.of(), null)
            );
        } catch (Exception e) {
            LOG.error("Failed to lookup score id", e);
        }

        if (scoreId == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "获取成绩失败，请稍后再试喵~"));
            return;
        }

        final RankGuessGameService.WishResult wish = games.wishScore(ctx.groupId(), scoreId);

        ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + switch (wish) {
            case SUCCESS -> "小星听到你的愿望啦！";
            case ALREADY_WISHED -> "已经许过愿了喵~";
            case RECENTLY_PICKED -> "最近已经被抽到过了喵~";
            case null -> "发生了一些不好的事情...";
        }));
    }

    private void start(Context ctx, boolean fromGroup) {
        Reservation reservation = games.reserve(ctx.groupId(), ctx.senderUserId(), fromGroup);
        if (reservation == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "本群已有一轮 Rank Guess 正在进行。"));
            return;
        }

        boolean activated = false;
        try (var _ = taskCoordinator.beginRequest(ctx, "Rank Guess Render")) {
            final PendingMessage message = PendingMessage.ofMarkdownRaw(at(ctx) + RandomReply.loading());
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

            Round round = Round.from(randomScore, activeMessageEnabled);

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

            var warning = SCHEDULER.schedule(
                    () -> {
                        ctx.sendReply(PendingMessage.ofMarkdownRaw(
                                "回放渲染时间超过预期，可" + cmd("/rstat " + renderTask.taskId(), "点击查看渲染进度") + "喵~")
                        );
                    },
                    60,
                    TimeUnit.SECONDS
            );

            APIHelper.ReplayRenderResult replay = null;
            try {
                replay = taskCoordinator.waitForReplay(renderTask);
            } catch (Exception e) {
                LOG.error("Failed to render replay for rank guess", e);
            } finally {
                warning.cancel(false);
            }

            if (replay == null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw("由于回放渲染失败，本轮游戏已取消~"));
                return;
            }

            final PendingMessage videoMessage = taskCoordinator.replayVideoMessage(replay);
            SendResult sendResult = ctx.sendReply(videoMessage);

            if (!sendResult.success()) {
                sendResult = ctx.sendMessage(videoMessage);
            }

            if (!sendResult.success()) {
                taskCoordinator.removeReplayResult(renderTask.taskId());
                final PendingMessage cancelMessage = PendingMessage.ofMarkdownRaw("由于回放发送失败，本轮游戏已取消~");
                if (!ctx.sendReply(cancelMessage).success()) {
                    ctx.sendMessage(cancelMessage);
                }
                return;
            }

            taskCoordinator.removeReplayResult(renderTask.taskId());
            var game = games.activate(reservation, round, MessageReference.of(sendResult.sentMessage()));

            if (game == null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw("无法开始游戏，请稍后再试喵"));
                return;
            }

            activated = true;

            StringBuilder result = new StringBuilder("回放渲染完成，游戏已开始！请在群内发送 `/rg #Rank` 猜测排名~");

            if (fromGroup) {
                result.append("\n").append("__Tip: 这是一位群友的成绩喵~__").append("\n");
            }

            var hints = HintUtil.prepareHints(round.getNormalHints(), 4);

            if (!activeMessageEnabled) {
                result.append("\n").append("> 提示: ");
                for (RankGuessGame.Hint s : hints) {
                    result.append("\n").append("> ").append(s.content());
                }
            } else {
                result.append("\n").append("> 第一个提示将在 1 分钟后揭晓~");
            }

            boolean startMessageSent = ctx.sendReply(
                    PendingMessage.ofMarkdownRaw(result.toString().trim())
            ).success();

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

                boolean hasExactGuess = game.getGuesses().values().stream()
                        .anyMatch(guess ->
                                guess.rank() == game.getRound().actualRank()
                        );

                boolean hasOutstandingGuess = game.getGuesses()
                        .values()
                        .stream()
                        .anyMatch(guess ->
                                RankGuessGameService.isOutstandingGuess(
                                        guess.rank(),
                                        game.getRound().actualRank(),
                                        game.getGuesses().size(),
                                        game.getRevealedHints().size(),
                                        hints.size()
                                )
                        );

                if (hasOutstandingGuess) {
                    String hintContent = "__猜Rank提示：__\n"
                            + "- 有人已经做出了非常精准的猜测！游戏将在 30 秒后结束喵~\n"
                            + hintString;

                    ctx.sendMessage(PendingMessage.ofMarkdownRaw(hintContent.trim()));
                    break;
                }

                final RankGuessGame.Hint hint = hints.removeFirst();

                hintString.insert(0, "- " + hint.content() + "\n");

                String hintContent = "__猜Rank提示：__\n" + hintString;

                if (!hints.isEmpty()) {
                    hintContent += "\n> 下一个提示将在 30 秒后揭晓~";
                } else {
                    hintContent += "\n> 所有提示已经揭晓啦！游戏将在 30 秒后自动结束~";
                }

                if (ctx.sendMessage(PendingMessage.ofMarkdownRaw(hintContent)).success()) {
                    game.revealHint(hint);
                }
            }

            try {
                Thread.sleep(30 * 1000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }

            if (!game.isEnded()) {
                end(ctx, true);
            }
        } finally {
            if (!activated) {
                games.cancel(reservation);
            }
        }
    }

    private void guess(Context ctx, long rank) {
        GuessResponse response = games.guess(ctx.groupId(), ctx.senderUserId(), rank);
        final GuessResult result = response.guessResult();
        PendingMessage message = switch (result.status()) {
            case NO_GAME -> PendingMessage.ofMarkdownRaw(at(ctx) + "本群当前没有进行中的 Rank Guess 喵");
            case STARTING -> PendingMessage.ofMarkdownRaw(at(ctx) + "回放仍在渲染，请等待视频发送后再猜测喵");
            case TOO_SOON -> PendingMessage.ofMarkdownRaw(at(ctx) + "距离上次猜测不足20秒，无法修改猜测喵");
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
    }

    private void end(Context ctx, boolean force) {
        EndResult result;
        try {
            result = games.end(ctx.groupId(), ctx.senderUserId(), adminAuthorizer.test(ctx.senderUserId()), force);
        } catch (RankGuessRecordStore.RecordSaveException e) {
            LOG.error("Failed to record rank guess round in group {}", ctx.groupId(), e);
            PendingMessage failure = PendingMessage.ofMarkdownRaw(at(ctx) + "战绩保存失败，本轮尚未结算，请稍后使用 /rg end 重试喵。");
            if (!ctx.sendReply(failure).success()) ctx.sendMessage(failure);
            return;
        }

        PendingMessage message = switch (result.status()) {
            case NO_GAME -> PendingMessage.ofMarkdownRaw(at(ctx) + "本群当前没有进行中的 Rank Guess 喵");
            case STARTING -> PendingMessage.ofMarkdownRaw(at(ctx) + "高光仍在渲染，请等待视频发送后再结束游戏喵");
            case FORBIDDEN -> PendingMessage.ofMarkdownRaw(at(ctx) +
                    "开始猜测后的3分钟内，仅发起者和机器人管理员可以结束游戏喵"
            );
            case FINISHED -> replyFactory.rankGuessResultMessage(ctx, result.round(), result.rankType());
        };

        if (!ctx.sendReply(message).success()) {
            ctx.sendMessage(message);
        }
    }

    enum LeaderboardType {
        GROUP_SELF, GROUP_FULL, GROUP_RANGE, GLOBAL_SELF, GLOBAL_RANGE
    }

    record LeaderboardRange(int start, int end) {
    }
}

final class LeaderboardHandler {
    static void leaderboard(Context ctx, RankGuessCommandHandler.LeaderboardType type) {
        leaderboard(ctx, type, null, null);
    }

    static void leaderboard(
            Context ctx, RankGuessCommandHandler.LeaderboardType type, Integer start, Integer end
    ) {
        try {
            final StringBuilder reply = new StringBuilder();

            final String effectiveGroupId =
                    (type == RankGuessCommandHandler.LeaderboardType.GLOBAL_SELF || type == RankGuessCommandHandler.LeaderboardType.GLOBAL_RANGE)
                            ? null
                            : ctx.groupId();

            final Map<String, RankGuessRecordStore.RankData> rankData =
                    RankGuessRecordStore.getGroupRankData(
                            effectiveGroupId,
                            null,
                            Rank.RECENT_GAME_LIMIT,
                            Rank.STATS_MIN_PARTICIPANTS,
                            RankGuessGameService.MIN_GAMES_TO_RANK
                    );

            final List<Map.Entry<String, Rank>> ranks =
                    rankData.entrySet().stream()
                            .map(entry -> Map.entry(entry.getKey(), Rank.from(entry.getValue())))
                            .filter(entry -> UserDataStore.findBoundUid(entry.getKey()) != null)
                            .sorted(Comparator.<Map.Entry<String, Rank>>comparingDouble(
                                                    entry -> entry.getValue().rating()
                                            )
                                            .reversed()
                                            .thenComparing(Map.Entry::getKey)
                            )
                            .toList();

            if (type == RankGuessCommandHandler.LeaderboardType.GROUP_RANGE || type == RankGuessCommandHandler.LeaderboardType.GLOBAL_RANGE) {

                if (start == null || end == null
                        || start < 1 || end < start || end - start + 1 > RankGuessCommandHandler.MAX_LEADERBOARD_RANGE) {
                    throw new IllegalArgumentException(
                            "Invalid leaderboard range: " + start + "-" + end
                    );
                }
            }

            switch (type) {
                case GROUP_FULL -> groupLeaderboard(ctx, reply, ranks, 0, ranks.size());
                case GROUP_RANGE -> groupLeaderboard(ctx, reply, ranks, start - 1, Math.min(end, ranks.size()));
                case GROUP_SELF -> selfLeaderboard(ctx, reply, effectiveGroupId, ranks);
                case GLOBAL_SELF -> globalLeaderboard(ctx, reply, ranks);
                case GLOBAL_RANGE -> globalRangeLeaderboard(ctx, reply, ranks, start, end);
            }

            ctx.sendReply(PendingMessage.ofMarkdownRaw(reply.toString().trim()));
        } catch (RuntimeException e) {
            RankGuessCommandHandler.LOG.error("Failed to query rank guess statistics", e);

            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "战绩查询失败，请稍后重试喵。"));
        }
    }

    private static void globalRangeLeaderboard(
            Context ctx, StringBuilder reply, List<Map.Entry<String, Rank>> ranks, int start, int end
    ) {
        reply.append(at(ctx))
                .append("全局猜 Rank 战绩排行：\n");

        final int from = start - 1;
        final int to = Math.min(end, ranks.size());

        if (from >= to) {
            reply.append("> (该范围暂无玩家)");
            return;
        }

        for (int i = from; i < to; i++) {
            final Map.Entry<String, Rank> rank = ranks.get(i);

            reply.append("> __\\#")
                    .append(i + 1)
                    .append("__: ")
                    .append(getLeaderboardName(rank.getKey()))
                    .append(" (%.2f)".formatted(rank.getValue().rating()))
                    .append("\n");
        }
    }

    private static String getLeaderboardName(String userId) {
        return Optional.ofNullable(userId)
                .map(UserDataStore::findBoundUid)
                .flatMap(UserDataStore::findUsername)
                .orElse("未知");
    }

    private static void globalLeaderboard(
            Context ctx, StringBuilder reply, List<Map.Entry<String, Rank>> ranks
    ) {
        final String userId = ctx.senderUserId();

        if (!RankGuessRecordStore.canBeRanked(userId, null)) {
            reply.append(at(ctx))
                    .append("你还未在全局参加过猜 Rank，或参与次数不足喵~");
            return;
        }

        int placement = -1;

        for (int i = 0; i < ranks.size(); i++) {
            if (ranks.get(i).getKey().equals(userId)) {
                placement = i;
                break;
            }
        }

        if (placement < 0) {
            reply.append(at(ctx))
                    .append("你还未在全局参加过猜 Rank，或参与次数不足喵~");
            return;
        }

        final double selfRating =
                ranks.get(placement).getValue().rating();

        reply.append(at(ctx))
                .append("你在全局猜 Rank 战绩排行第 __")
                .append(placement + 1)
                .append("__ 名！\n");

        reply.append("以下是你附近的玩家：\n");

        final int window = 5;
        final int from = Math.max(0, placement - window);
        final int to = Math.min(ranks.size(), placement + window + 1);

        for (int i = from; i < to; i++) {
            final Map.Entry<String, Rank> rank = ranks.get(i);
            final double otherRating = rank.getValue().rating();

            reply.append("> __\\#")
                    .append(i + 1)
                    .append("__: ")
                    .append(getLeaderboardName(rank.getKey()))
                    .append(" (%.2f) (%+.3f)".formatted(
                            otherRating,
                            otherRating - selfRating
                    ))
                    .append("\n");
        }
    }

    private static void selfLeaderboard(
            Context ctx, StringBuilder reply, String groupId, List<Map.Entry<String, Rank>> ranks
    ) {
        final String userId = ctx.senderUserId();

        if (!RankGuessRecordStore.canBeRanked(userId, groupId)) {
            reply.append(at(ctx))
                    .append("你还未在本群参加过猜 Rank，或参与次数不足喵~");
            return;
        }

        int placement = -1;

        for (int i = 0; i < ranks.size(); i++) {
            if (ranks.get(i).getKey().equals(userId)) {
                placement = i;
                break;
            }
        }

        if (placement < 0) {
            reply.append(at(ctx)).append("你还未在本群参加过猜 Rank，或参与次数不足喵~");
            return;
        }

        final double selfRating =
                ranks.get(placement).getValue().rating();

        reply.append(at(ctx))
                .append("你在本群猜 Rank 战绩排行第 __")
                .append(placement + 1)
                .append("__ 名！\n");

        reply.append("以下是你附近的玩家：\n");

        final int window = 3;
        final int from = Math.max(0, placement - window);
        final int to = Math.min(ranks.size(), placement + window + 1);

        for (int i = from; i < to; i++) {
            final Map.Entry<String, Rank> rank = ranks.get(i);
            final double otherRating = rank.getValue().rating();

            reply.append("> __\\#")
                    .append(i + 1)
                    .append("__: ")
                    .append(getLeaderboardName(rank.getKey()))
                    .append(" (%.2f) (%+.3f)".formatted(
                            otherRating,
                            otherRating - selfRating
                    ))
                    .append("\n");
        }
    }

    private static void groupLeaderboard(
            Context ctx, StringBuilder reply, List<Map.Entry<String, Rank>> ranks, int from, int to
    ) {
        reply.append(at(ctx)).append("本群猜 Rank 战绩排行：\n");

        if (ranks.isEmpty()) {
            reply.append("> (暂无玩家)");
            return;
        }

        if (from >= to) {
            reply.append("> (该范围暂无玩家)");
            return;
        }

        for (int i = from; i < to; i++) {
            final Map.Entry<String, Rank> rank = ranks.get(i);

            reply.append("> __\\#")
                    .append(i + 1)
                    .append("__: ")
                    .append(getLeaderboardName(rank.getKey()))
                    .append(" (%.2f)".formatted(rank.getValue().rating()))
                    .append("\n");
        }
    }
}

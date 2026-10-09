package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.OstellaApi;
import xyz.zcraft.seira.api.data.MissData;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.challenge.ChallengeService;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.*;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class ScoreCommandHandler {
    private static final int MAX_SCORE_LIST_COUNT = 200;
    private static final Pattern SCORE_LIST_RANGE_PATTERN = Pattern.compile("^(\\d+)(?:-(\\d+))?$");

    private final Resolver resolver;
    private final TargetResolver targets;
    private final TargetHistory history;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final ChallengeService challengeService;

    public ScoreCommandHandler(
            Resolver resolver,
            TargetHistory history,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            java.util.function.Function<String, String> accessTokenProvider,
            ChallengeService challengeService
    ) {
        this.challengeService = challengeService;
        this.resolver = resolver;
        this.targets = new TargetResolver(resolver, accessTokenProvider);
        this.history = history;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
    }

    static TbArguments parseTbArguments(String[] args) {
        int days = 1;
        int targetIndex = 0;
        if (args.length > 0 && args[0].startsWith("#")) {
            try {
                days = Integer.parseInt(args[0].substring(1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (days <= 0) return null;
            targetIndex = 1;
        }
        if (args.length - targetIndex > 1) return null;
        return new TbArguments(days, args.length > targetIndex ? args[targetIndex] : null);
    }

    static ScoreListRange parseScoreListRange(String value) {
        Matcher matcher = SCORE_LIST_RANGE_PATTERN.matcher(value);
        if (!matcher.matches()) return null;
        try {
            int first = Integer.parseInt(matcher.group(1));
            String endGroup = matcher.group(2);
            int start = endGroup == null ? 1 : first;
            int end = endGroup == null ? first : Integer.parseInt(endGroup);
            if (start <= 0 || start > end || end > MAX_SCORE_LIST_COUNT) return null;
            return new ScoreListRange(start, end);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    void collectChallengeScores(Context ctx, Response<?> response) {
        if (!ctx.inGroup()) return;
        var ids = new java.util.LinkedHashSet<Long>();
        var values = new java.util.ArrayList<String>();
        if (response.getScoreId() != null) values.add(response.getScoreId());
        if (response.getScoreIds() != null) values.addAll(response.getScoreIds());
        for (String value : values) {
            try {
                ids.add(Long.parseLong(value.trim()));
            } catch (NumberFormatException ignored) {
                // Ignore malformed optional response metadata.
            }
        }
        try {
            challengeService.acceptQueriedScores(ctx.groupId(), ids);
        } catch (RuntimeException e) {
            org.apache.logging.log4j.LogManager.getLogger(ScoreCommandHandler.class)
                    .warn("Failed to update group challenge from score query", e);
        }
    }

    public void handleBp(Context ctx) {
        if (ctx.args().length == 0) {
            String player = resolver.player(null, ctx.senderUserId());
            try (var _ = taskCoordinator.beginRequest(ctx, "Score")) {
                long uid = OstellaApi.resolveUid(player);
                String scoreId = OstellaApi.lookupPlayerScore(uid, "bp", 1, List.of(), null);
                var response = OstellaApi.getScoreResponse(scoreId);
                collectChallengeScores(ctx, response);
                history.remember(ctx, null, null, scoreId);
                ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.scoreMessage(ctx, response)));
            }
            return;
        }

        if (resolver.looksLikeMention(ctx.args()[0])) {
            if (ctx.args().length == 1 || (ctx.args().length > 1 && ScoreFilterArguments.looksLikeFilter(ctx.args()[1]))) {
                handleFilteredSingleScore(ctx, "bp");
                return;
            }
        } else if (ScoreFilterArguments.looksLikeFilter(ctx.args()[0])) {
            handleFilteredSingleScore(ctx, "bp");
            return;
        }

        var request = parseScoreListRequest(ctx, CommandUsage.BP);
        if (request == null) return;
        var range = request.range();
        var player = request.player();
        var filters = request.filters();
        try (var _ = taskCoordinator.beginRequest(ctx, "Best Scores")) {
            long uid = OstellaApi.resolveUid(player);
            var response = OstellaApi.getBoNResponse(
                    range.end(),
                    range.start(),
                    uid,
                    filters.filters()
            );
            collectChallengeScores(ctx, response);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.bpMessage(ctx, response)));
        }
    }

    public void handleRbp(Context ctx) {
        if (ctx.argumentCount() > 1) {
            ctx.sendReply(at(ctx) + "用法：/rbp [目标]");
            return;
        }

        String player = resolver.player(ctx.argumentCount() == 1 ? ctx.argument(0) : null, ctx.senderUserId());

        try (var _ = taskCoordinator.beginRequest(ctx, "Score")) {
            long uid = OstellaApi.resolveUid(player);
            String scoreId = OstellaApi.lookupPlayerScore(uid, "bp", ThreadLocalRandom.current().nextInt(200) + 1, List.of(), null);
            var response = OstellaApi.getScoreResponse(scoreId);
            collectChallengeScores(ctx, response);
            history.remember(ctx, null, null, scoreId);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.scoreMessage(ctx, response)));
        }
    }

    public void handleRs(Context ctx, boolean includeFail) {
        if (ctx.args().length == 0) {
            String player = resolver.player(null, ctx.senderUserId());
            try (var _ = taskCoordinator.beginRequest(ctx, "Score")) {
                long uid = OstellaApi.resolveUid(player);
                String scoreId = OstellaApi.lookupPlayerScore(uid, ctx.command(), 1, List.of(), null);
                var response = OstellaApi.getScoreResponse(scoreId);
                collectChallengeScores(ctx, response);
                history.remember(ctx, null, null, scoreId);
                ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.scoreMessage(ctx, response)));
            }
            return;
        }

        if (resolver.looksLikeMention(ctx.args()[0])) {
            if (ctx.args().length == 1 || (ctx.args().length > 1 && ScoreFilterArguments.looksLikeFilter(ctx.args()[1]))) {
                handleFilteredSingleScore(ctx, ctx.command());
                return;
            }
        } else if (ScoreFilterArguments.looksLikeFilter(ctx.args()[0])) {
            handleFilteredSingleScore(ctx, ctx.command());
            return;
        }

        var request = parseScoreListRequest(ctx, CommandUsage.RS);
        if (request == null) return;
        var range = request.range();
        var player = request.player();
        var filters = request.filters();
        try (var _ = taskCoordinator.beginRequest(ctx, "Recent Score")) {
            long uid = OstellaApi.resolveUid(player);
            var response = OstellaApi.getRecentResponse(
                    range.end(),
                    range.start(),
                    uid,
                    includeFail,
                    filters.filters()
            );
            collectChallengeScores(ctx, response);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.rsMessage(ctx, response)));
        }
    }

    public void handleTb(Context ctx) {
        TbArguments request = parseTbArguments(ctx.args());
        if (request == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.TB));
            return;
        }

        String player = resolver.player(request.target(), ctx.senderUserId());
        try (var _ = taskCoordinator.beginRequest(ctx, "Recent Best Scores")) {
            long uid = OstellaApi.resolveUid(player);
            var response = OstellaApi.getTodayBestResponse(uid, request.days());
            collectChallengeScores(ctx, response);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.tbMessage(ctx, response)));
        }
    }

    private void handleFilteredSingleScore(Context ctx, String macroType) {
        String targetUser = null;
        int startIndex = 0;

        if (resolver.looksLikeMention(ctx.args()[0]) || resolver.looksLikeUid(ctx.args()[0])) {
            targetUser = resolver.player(ctx.args()[0], ctx.senderUserId());
            startIndex = 1;
        }

        ScoreFilterArguments.ParseResult filters = ScoreFilterArguments.parse(ctx.args(), startIndex);
        if (filters.isError()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + filters.errorMessage() + "\n" + CommandUsage.SCORE_FILTERS));
            return;
        }

        if (targetUser == null) targetUser = resolver.player(null, ctx.senderUserId());

        try (var _ = taskCoordinator.beginRequest(ctx, "Score")) {
            long uid = OstellaApi.resolveUid(targetUser);
            String scoreId = OstellaApi.lookupPlayerScore(uid, macroType, 1, filters.filters(), null);
            var response = OstellaApi.getScoreResponse(scoreId);
            collectChallengeScores(ctx, response);
            history.remember(ctx, null, null, scoreId);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.scoreMessage(ctx, response)));
        }
    }

    public void handleS(Context ctx) {
        String usage = CommandUsage.S;
        TargetInput target;
        String userOverride = null;
        int optionIndex;
        boolean playerOnly = ctx.argumentCount() > 0 && resolver.looksLikeMention(ctx.argument(0))
                && (ctx.argumentCount() == 1 || ctx.argument(1).startsWith("+"));
        if (playerOnly || ctx.argumentCount() == 0 || ctx.argument(0).startsWith("+")) {
            target = TargetInput.memory();
        } else {
            target = TargetInput.readScoreTarget(ctx.args());
        }
        optionIndex = target.consumedArgs();
        if (optionIndex < ctx.argumentCount() && !ctx.argument(optionIndex).startsWith("+")) {
            userOverride = resolver.player(ctx.argument(optionIndex), ctx.senderUserId());
            optionIndex++;
        }
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - optionIndex > 1
                || (optionIndex < ctx.argumentCount() && !ctx.argument(optionIndex).startsWith("+"))) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + usage));
            return;
        }
        String option = optionIndex < ctx.argumentCount() ? ctx.argument(optionIndex) : null;
        String mod = option == null ? null : option.substring(1);
        List<String> filters = List.of();
        if (mod != null) {
            var parsed = ScoreFilterArguments.parse(new String[]{"mod=" + mod}, 0);
            if (parsed.isError()) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + parsed.errorMessage()));
                return;
            }
            filters = parsed.filters();
        }
        try (var _ = taskCoordinator.beginRequest(ctx, "Score")) {
            var ids = targets.score(ctx, target, remembered, userOverride, filters, mod);
            var response = OstellaApi.getScoreResponse(ids.scoreId());
            collectChallengeScores(ctx, response);
            history.remember(ctx, ids);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.scoreMessage(ctx, response)));
        } catch (Exception e) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + TaskCoordinator.resolveErrorMessage(e)));
            org.apache.logging.log4j.LogManager.getLogger(ScoreCommandHandler.class)
                    .error("Failed to execute /{}", ctx.command(), e);
        }
    }

    public void handleSm(Context ctx) {
        String usage = CommandUsage.SM;
        TargetInput target;
        String userOverride = null;
        int optionIndex;
        boolean playerOnly = ctx.argumentCount() > 0 && resolver.looksLikeMention(ctx.argument(0))
                && (ctx.argumentCount() == 1 || ctx.argument(1).startsWith("+"));
        if (playerOnly || ctx.argumentCount() == 0 || ctx.argument(0).startsWith("+")) {
            target = TargetInput.memory();
        } else {
            target = TargetInput.read(ctx.args());
        }
        optionIndex = target.consumedArgs();
        if (optionIndex < ctx.argumentCount() && !ctx.argument(optionIndex).startsWith("+")) {
            userOverride = resolver.player(ctx.argument(optionIndex), ctx.senderUserId());
            optionIndex++;
        }
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - optionIndex > 1
                || (optionIndex < ctx.argumentCount() && !ctx.argument(optionIndex).startsWith("+"))) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + usage));
            return;
        }
        String option = optionIndex < ctx.argumentCount() ? ctx.argument(optionIndex) : null;
        String mod = option == null ? null : option.substring(1);
        List<String> filters = List.of();
        if (mod != null) {
            var parsed = ScoreFilterArguments.parse(new String[]{"mod=" + mod}, 0);
            if (parsed.isError()) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + parsed.errorMessage()));
                return;
            }
            filters = parsed.filters();
        }
        try (var _ = taskCoordinator.beginRequest(ctx, "Score")) {
            var ids = targets.beatmap(ctx, target, remembered);
            Long beatmapId = ids.beatmapId();
            Long beatmapsetId = ids.beatmapsetId();
            String player = userOverride == null ? resolver.player(null, ctx.senderUserId()) : userOverride;
            long uid = OstellaApi.resolveUid(player);
            String scoreId = OstellaApi.lookupBeatmapScore(beatmapId, uid, filters, mod);
            var response = OstellaApi.getScoreResponse(scoreId);
            collectChallengeScores(ctx, response);
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.scoreMessage(ctx, response)));
        } catch (Exception e) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + TaskCoordinator.resolveErrorMessage(e)));
            org.apache.logging.log4j.LogManager.getLogger(ScoreCommandHandler.class)
                    .error("Failed to execute /{}", ctx.command(), e);
        }
    }

    public void handleSa(Context ctx) {
        var target = ctx.argumentCount() == 0
                ? TargetInput.memory() : TargetInput.readScoreTarget(ctx.args());
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - target.consumedArgs() > 0) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.SA));
            return;
        }
        try (var _ = taskCoordinator.beginRequest(ctx, "Score Analysis")) {
            var ids = targets.score(ctx, target, remembered);
            var response = OstellaApi.getScoreAnalyzeResponse(ids.scoreId());
            collectChallengeScores(ctx, response);
            history.remember(ctx, ids);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.scoreAnalyzeMessage(ctx, response)));
        }
    }

    public void handleMa(Context ctx) {
        var target = ctx.argumentCount() == 0 || ctx.argument(0).startsWith("#")
                ? TargetInput.memory() : TargetInput.readScoreTarget(ctx.args());
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - target.consumedArgs() > 1) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.MA));
            return;
        }
        String indexArgument = (ctx.argumentCount() > target.consumedArgs() ? ctx.argument(target.consumedArgs()) : null);
        Integer index = indexArgument == null ? null : parseMissIndex(indexArgument, target.kind() == TargetInput.Kind.MEMORY);
        if (indexArgument != null && index == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.MA));
            return;
        }
        try (var _ = taskCoordinator.beginRequest(ctx, "Score Misses")) {
            var ids = targets.score(ctx, target, remembered);
            var response = OstellaApi.getScoreMissesResponse(ids.scoreId());
            collectChallengeScores(ctx, response);
            history.remember(ctx, ids);
            List<MissData> misses = response.getContent();
            if (index == null && misses.size() != 1) {
                ctx.sendReply(replyFactory.scoreMissesMessage(ctx, response));
                return;
            }
            if (misses.isEmpty()) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "本成绩没有Miss喵~"));
                return;
            }
            int selectedIndex = index == null ? 1 : index;
            if (selectedIndex > misses.size()) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "Miss序号不在范围内喵(1~" + misses.size() + ")"));
                return;
            }
            var image = OstellaApi.getMissVisualizeResponse(ids.scoreId(), selectedIndex);
            ctx.sendReply(taskCoordinator.imageMessage(image,
                    replyFactory.missImageMessage(ctx, ids.scoreId(), selectedIndex, misses.size())));
        }
    }

    private Integer parseMissIndex(String arg, boolean requirePrefix) {
        String value = arg;
        if (arg.startsWith("#")) {
            value = arg.substring(1);
        } else if (requirePrefix) {
            return null;
        }
        return resolver.parsePositiveInt(value);
    }

    public void handleSnapshot(Context ctx) {
        handleReplayImage(ctx, false);
    }

    public void handleReplayClip(Context ctx) {
        handleReplayImage(ctx, true);
    }

    private void handleReplayImage(Context ctx, boolean animated) {
        String usage = animated ? CommandUsage.REP : CommandUsage.SNAP;
        if (ctx.argumentCount() == 0) {
            ctx.sendReply(at(ctx) + usage);
            return;
        }
        var target = SnapshotSelection.looksLikeSelector(ctx.argument(0))
                ? TargetInput.memory() : TargetInput.readScoreTarget(ctx.args());
        var remembered = history.get(ctx);
        int selectorIndex = target.consumedArgs();
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() < selectorIndex + 1 || ctx.argumentCount() > selectorIndex + (animated ? 3 : 2)) {
            ctx.sendReply(at(ctx) + usage);
            return;
        }
        final SnapshotSelection selection;
        final xyz.zcraft.seira.command.parse.ReplayWindow window;
        try {
            int next = selectorIndex + 1;
            String offset = next < ctx.argumentCount() && (!animated || ctx.argument(next).endsWith("ms"))
                    ? ctx.argument(next++) : null;
            selection = SnapshotSelection.parse(ctx.argument(selectorIndex), offset);
            window = animated
                    ? xyz.zcraft.seira.command.parse.ReplayWindow.parse(
                            next < ctx.argumentCount()
                                    ? ctx.argument(next++)
                                    : null
                    )
                    : null;
            if (next != ctx.argumentCount()) throw new IllegalArgumentException("参数过多。");
        } catch (IllegalArgumentException e) {
            ctx.sendReply(at(ctx) + e.getMessage() + "\n" + usage);
            return;
        }
        try (var _ = taskCoordinator.beginRequest(ctx, animated ? "Replay GIF" : "Replay Snapshot")) {
            if (target.kind() == TargetInput.Kind.MEMORY
                    && remembered.scoreId() == null && remembered.beatmapId() == null)
                throw new ResolutionException("请指定指令目标成绩喵");
            var ids = targets.score(ctx, target, remembered);
            Long beatmapId = ids.beatmapId();
            Long beatmapsetId = ids.beatmapsetId();
            String scoreId = ids.scoreId();
            var response = animated ? OstellaApi.getReplayClipResponse(scoreId, selection, window)
                    : OstellaApi.getReplaySnapshotResponse(scoreId, selection);
            collectChallengeScores(ctx, response);
            if (response.getBeatmapId() != null) beatmapId = Long.parseLong(response.getBeatmapId());
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
            ctx.sendReply(taskCoordinator.imageMessage(response,
                    animated ? replyFactory.replayClipMessage(ctx, scoreId, selection.label())
                            : replyFactory.snapshotImageMessage(ctx, scoreId, selection.label())));
        }
    }

    private ScoreListRequest parseScoreListRequest(Context ctx, String usage) {
        String[] args = ctx.args();
        ScoreListRange range = parseScoreListRange(args[0]);
        if (range == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + usage
                    + "\n数量或范围必须在 1 到 " + MAX_SCORE_LIST_COUNT + " 之间，且范围起点不能大于终点。"));
            return null;
        }

        int nextArg = 1;
        String player;
        if (nextArg < args.length && (resolver.looksLikeMention(args[nextArg]) || resolver.looksLikeUid(args[nextArg]))) {
            player = resolver.player(args[nextArg], ctx.senderUserId());
            nextArg++;
        } else {
            player = resolver.player(null, ctx.senderUserId());
        }

        ScoreFilterArguments.ParseResult filters = ScoreFilterArguments.parse(args, nextArg);
        if (filters.isError()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + filters.errorMessage() + "\n" + CommandUsage.SCORE_FILTERS));
            return null;
        }
        return new ScoreListRequest(range, player, filters);
    }

    private record ScoreListRequest(ScoreListRange range, String player, ScoreFilterArguments.ParseResult filters) {
    }

    record TbArguments(int days, String target) {
    }

    record ScoreListRange(int start, int end) {
    }
}

package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.ApiHelper;
import xyz.zcraft.seira.api.data.MissData;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.parse.TargetInput;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.ScoreFilterArguments;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class ScoreCommandHandler {
    private static final int MAX_SCORE_LIST_COUNT = 200;
    private static final Pattern SCORE_LIST_RANGE_PATTERN = Pattern.compile("^(\\d+)(?:-(\\d+))?$");

    private final java.util.function.Function<String, String> accessTokenProvider;
    private final Resolver resolver;
    private final TargetHistory history;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;

    public ScoreCommandHandler(
            Resolver resolver,
            TargetHistory history,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            java.util.function.Function<String, String> accessTokenProvider
    ) {
        this.resolver = resolver;
        this.accessTokenProvider = accessTokenProvider;
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

    public void handleBp(Context ctx) {
        if (ctx.args().length == 0) {
            String player = resolver.player(null, ctx.senderUserId());
            try (var _ = taskCoordinator.beginRequest(ctx, "Score")) {
                long uid = ApiHelper.resolveUid(player);
                String scoreId = ApiHelper.lookupPlayerScore(uid, "bp", 1, List.of(), null);
                var response = ApiHelper.getScoreResponse(scoreId);
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
            long uid = ApiHelper.resolveUid(player);
            var response = ApiHelper.getBoNResponse(
                    range.end(),
                    range.start(),
                    uid,
                    filters.filters()
            );
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.bpMessage(ctx, response)));
        }
    }

    public void handleRs(Context ctx, boolean includeFail) {
        if (ctx.args().length == 0) {
            String player = resolver.player(null, ctx.senderUserId());
            try (var _ = taskCoordinator.beginRequest(ctx, "Score")) {
                long uid = ApiHelper.resolveUid(player);
                String scoreId = ApiHelper.lookupPlayerScore(uid, ctx.command(), 1, List.of(), null);
                var response = ApiHelper.getScoreResponse(scoreId);
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
            long uid = ApiHelper.resolveUid(player);
            var response = ApiHelper.getRecentResponse(
                    range.end(),
                    range.start(),
                    uid,
                    includeFail,
                    filters.filters()
            );
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
            long uid = ApiHelper.resolveUid(player);
            var response = ApiHelper.getTodayBestResponse(uid, request.days());
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
            long uid = ApiHelper.resolveUid(targetUser);
            String scoreId = ApiHelper.lookupPlayerScore(uid, macroType, 1, filters.filters(), null);
            var response = ApiHelper.getScoreResponse(scoreId);
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
        String mod = option == null ? null : option.substring(1).toUpperCase(java.util.Locale.ROOT);
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
            var previous = target.kind() == TargetInput.Kind.MEMORY ? remembered : null;
            Long beatmapId = previous == null ? null : previous.beatmapId();
            Long beatmapsetId = previous == null ? null : previous.beatmapsetId();
            String scoreId = previous == null ? null : previous.scoreId();
            boolean selectedPlayerScore = false;
            switch (target.kind()) {
                case ID, SCORE -> scoreId = target.id();
                case MAP -> beatmapId = Long.parseLong(target.id());
                case SET -> {
                    beatmapsetId = Long.parseLong(target.id());
                    String player = userOverride == null ? resolver.player(null, ctx.senderUserId()) : userOverride;
                    long uid = ApiHelper.resolveUid(player);
                    scoreId = ApiHelper.lookupBeatmapsetScore(beatmapsetId, target.index(), uid, filters, mod);
                    selectedPlayerScore = true;
                }
                case RS, RP, BP -> {
                    String player = userOverride == null ? resolver.player(target.player(), ctx.senderUserId()) : userOverride;
                    long uid = ApiHelper.resolveUid(player);
                    scoreId = ApiHelper.lookupPlayerScore(uid, target.scoreList(), target.index(), filters, mod);
                    selectedPlayerScore = true;
                }
                case MP -> beatmapId = ApiHelper.lookupMultiplayerBeatmap(accessTokenProvider.apply(ctx.senderUserId()));
                case MEMORY -> {}
            }
            if ((userOverride != null || mod != null) && scoreId != null && !selectedPlayerScore) {
                if (beatmapId == null && scoreId != null) beatmapId = ApiHelper.getScoreBeatmapId(scoreId);
                scoreId = null;
            }
            if (scoreId == null) {
                if (beatmapId == null) throw new ResolutionException("请指定指令目标谱面喵");
                String player = userOverride == null ? resolver.player(null, ctx.senderUserId()) : userOverride;
                long uid = ApiHelper.resolveUid(player);
                scoreId = ApiHelper.lookupBeatmapScore(beatmapId, uid, filters, mod);
            }
            var response = ApiHelper.getScoreResponse(scoreId);
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
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
        String mod = option == null ? null : option.substring(1).toUpperCase(java.util.Locale.ROOT);
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
            var previous = target.kind() == TargetInput.Kind.MEMORY ? remembered : null;
            Long beatmapId = previous == null ? null : previous.beatmapId();
            Long beatmapsetId = previous == null ? null : previous.beatmapsetId();
            String scoreId = previous == null ? null : previous.scoreId();
            switch (target.kind()) {
                case ID, MAP -> beatmapId = Long.parseLong(target.id());
                case SCORE -> scoreId = target.id();
                case SET -> {
                    beatmapsetId = Long.parseLong(target.id());
                    beatmapId = ApiHelper.lookupBeatmapInSet(beatmapsetId, target.index(), accessTokenProvider.apply(ctx.senderUserId()));
                }
                case RS, RP, BP -> {
                    String player = resolver.player(target.player(), ctx.senderUserId());
                    long uid = ApiHelper.resolveUid(player);
                    scoreId = ApiHelper.lookupPlayerScore(uid, target.scoreList(), target.index(), List.of(), null);
                }
                case MP -> beatmapId = ApiHelper.lookupMultiplayerBeatmap(accessTokenProvider.apply(ctx.senderUserId()));
                case MEMORY -> {}
            }
            if (beatmapId == null && scoreId != null) beatmapId = ApiHelper.getScoreBeatmapId(scoreId);
            if (beatmapId == null) throw new ResolutionException("请指定指令目标谱面喵");
            String player = userOverride == null ? resolver.player(null, ctx.senderUserId()) : userOverride;
            long uid = ApiHelper.resolveUid(player);
            scoreId = ApiHelper.lookupBeatmapScore(beatmapId, uid, filters, mod);
            var response = ApiHelper.getScoreResponse(scoreId);
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
                ? TargetInput.memory() : TargetInput.read(ctx.args());
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - target.consumedArgs() > 0) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.SA));
            return;
        }
        try (var _ = taskCoordinator.beginRequest(ctx, "Score Analysis")) {
            var previous = target.kind() == TargetInput.Kind.MEMORY ? remembered : null;
            Long beatmapId = previous == null ? null : previous.beatmapId();
            Long beatmapsetId = previous == null ? null : previous.beatmapsetId();
            String scoreId = previous == null ? null : previous.scoreId();
            switch (target.kind()) {
                case ID, SCORE -> scoreId = target.id();
                case MAP -> beatmapId = Long.parseLong(target.id());
                case SET -> {
                    beatmapsetId = Long.parseLong(target.id());
                    String player = resolver.player(target.player(), ctx.senderUserId());
                    long uid = ApiHelper.resolveUid(player);
                    scoreId = ApiHelper.lookupBeatmapsetScore(beatmapsetId, target.index(), uid, List.of(), null);
                }
                case RS, RP, BP -> {
                    String player = resolver.player(target.player(), ctx.senderUserId());
                    long uid = ApiHelper.resolveUid(player);
                    scoreId = ApiHelper.lookupPlayerScore(uid, target.scoreList(), target.index(), List.of(), null);
                }
                case MP -> beatmapId = ApiHelper.lookupMultiplayerBeatmap(accessTokenProvider.apply(ctx.senderUserId()));
                case MEMORY -> {}
            }
            if (scoreId == null) {
                if (beatmapId == null) throw new ResolutionException("请指定指令目标谱面喵");
                String player = resolver.player(target.player(), ctx.senderUserId());
                long uid = ApiHelper.resolveUid(player);
                scoreId = ApiHelper.lookupBeatmapScore(beatmapId, uid, List.of(), null);
            }
            var response = ApiHelper.getScoreAnalyzeResponse(scoreId);
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.scoreAnalyzeMessage(ctx, response)));
        }
    }

    public void handleMa(Context ctx) {
        var target = ctx.argumentCount() == 0 || ctx.argument(0).startsWith("#")
                ? TargetInput.memory() : TargetInput.read(ctx.args());
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
            var previous = target.kind() == TargetInput.Kind.MEMORY ? remembered : null;
            Long beatmapId = previous == null ? null : previous.beatmapId();
            Long beatmapsetId = previous == null ? null : previous.beatmapsetId();
            String scoreId = previous == null ? null : previous.scoreId();
            switch (target.kind()) {
                case ID, SCORE -> scoreId = target.id();
                case MAP -> beatmapId = Long.parseLong(target.id());
                case SET -> {
                    beatmapsetId = Long.parseLong(target.id());
                    String player = resolver.player(target.player(), ctx.senderUserId());
                    long uid = ApiHelper.resolveUid(player);
                    scoreId = ApiHelper.lookupBeatmapsetScore(beatmapsetId, target.index(), uid, List.of(), null);
                }
                case RS, RP, BP -> {
                    String player = resolver.player(target.player(), ctx.senderUserId());
                    long uid = ApiHelper.resolveUid(player);
                    scoreId = ApiHelper.lookupPlayerScore(uid, target.scoreList(), target.index(), List.of(), null);
                }
                case MP -> beatmapId = ApiHelper.lookupMultiplayerBeatmap(accessTokenProvider.apply(ctx.senderUserId()));
                case MEMORY -> {}
            }
            if (scoreId == null) {
                if (beatmapId == null) throw new ResolutionException("请指定指令目标谱面喵");
                String player = resolver.player(target.player(), ctx.senderUserId());
                long uid = ApiHelper.resolveUid(player);
                scoreId = ApiHelper.lookupBeatmapScore(beatmapId, uid, List.of(), null);
            }
            var response = ApiHelper.getScoreMissesResponse(scoreId);
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
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
            var image = ApiHelper.getMissVisualizeResponse(scoreId, selectedIndex);
            ctx.sendReply(taskCoordinator.imageMessage(image,
                    replyFactory.missImageMessage(ctx, scoreId, selectedIndex, misses.size())));
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

    private record ScoreListRequest(ScoreListRange range, String player, ScoreFilterArguments.ParseResult filters) {}

    record TbArguments(int days, String target) {
    }

    record ScoreListRange(int start, int end) {
    }
}

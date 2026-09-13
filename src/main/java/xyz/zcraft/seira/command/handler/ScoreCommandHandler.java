package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.*;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.UserRef;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static xyz.zcraft.seira.command.TargetHistory.Type.SCORE;
import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class ScoreCommandHandler {
    private static final int MAX_SCORE_LIST_COUNT = 200;
    private static final Pattern SCORE_LIST_RANGE_PATTERN = Pattern.compile("^(\\d+)(?:-(\\d+))?$");

    private final Resolver resolver;
    private final TargetHistory history;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;

    public ScoreCommandHandler(
            Resolver resolver,
            TargetHistory history,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory
    ) {
        this.resolver = resolver;
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

    public void handleBp(Context ctx) {
        if (ctx.args().length == 0) {
            ShortcutTarget target = resolver.parseTarget("bp1", ctx.senderUserId());
            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + target.errorMessage()));
                return;
            }
            try (var _ = taskCoordinator.beginRequest(ctx, "Score")) {
                var ids = history.resolve(ctx, SCORE, new TargetResolution(target, 0));
                history.remember(ctx, ids);
                String scoreId = ids.scoreId();
                var response = APIHelper.getScoreResponse(scoreId);
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

        ScoreListRequest request = parseScoreListRequest(ctx, CommandUsage.BP);
        if (request == null) return;
        try (var _ = taskCoordinator.beginRequest(ctx, "Best Scores")) {
            var response = APIHelper.getBoNResponse(
                    request.range().end(),
                    request.range().start(),
                    request.userRef(),
                    request.filters()
            );
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.bpMessage(ctx, response)));
        }
    }

    public void handleRs(Context ctx, boolean includeFail) {
        if (ctx.args().length == 0) {
            ShortcutTarget target = resolver.parseTarget(ctx.command() + "1", ctx.senderUserId());
            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + target.errorMessage()));
                return;
            }
            try (var _ = taskCoordinator.beginRequest(ctx, "Score")) {
                var ids = history.resolve(ctx, SCORE, new TargetResolution(target, 0));
                history.remember(ctx, ids);
                String scoreId = ids.scoreId();
                var response = APIHelper.getScoreResponse(scoreId);
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

        ScoreListRequest request = parseScoreListRequest(ctx, CommandUsage.RS);
        if (request == null) return;
        try (var _ = taskCoordinator.beginRequest(ctx, "Recent Score")) {
            var response = APIHelper.getRecentResponse(
                    request.range().end(),
                    request.range().start(),
                    request.userRef(),
                    includeFail,
                    request.filters()
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

        UserRef userRef;
        if (request.target() != null) {
            UserRefResolution resolution = resolver.resolveUserRefArgument(request.target());
            if (resolution.errorMessage() != null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + resolution.errorMessage()));
                return;
            }
            if (resolution.userRef() == null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.TB));
                return;
            }
            userRef = resolution.userRef();
        } else {
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.NO_BIND));
                return;
            }
            userRef = new UserRef.ByUid(uid);
        }

        UserRef target = userRef;
        try (var _ = taskCoordinator.beginRequest(ctx, "Recent Best Scores")) {
            var response = APIHelper.getTodayBestResponse(target, request.days());
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.tbMessage(ctx, response)));
        }
    }

    private void handleFilteredSingleScore(Context ctx, String macroType) {
        UserRef targetUser = null;
        int startIndex = 0;

        if (resolver.looksLikeMention(ctx.args()[0]) || resolver.looksLikeUid(ctx.args()[0])) {
            final UserRefResolution userRefResolution = resolver.resolveUserRefArgument(ctx.args()[0]);
            if (userRefResolution.errorMessage() != null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + userRefResolution.errorMessage()));
                return;
            }
            targetUser = userRefResolution.userRef();
            startIndex = 1;
        }

        ScoreFilterArguments.ParseResult filters = ScoreFilterArguments.parse(ctx.args(), startIndex);
        if (filters.isError()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + filters.errorMessage() + "\n" + CommandUsage.SCORE_FILTERS));
            return;
        }

        if (targetUser == null) {
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.NO_BIND));
                return;
            }
            targetUser = new UserRef.ByUid(uid);
        }

        ShortcutTarget target = new ShortcutTarget(null, targetUser, macroType, 1L, null);
        try (var _ = taskCoordinator.beginRequest(ctx, "Score")) {
            var ids = history.resolve(ctx, SCORE, new TargetResolution(target, 0), filters.filters(), null);
            history.remember(ctx, ids);
            String scoreId = ids.scoreId();
            var response = APIHelper.getScoreResponse(scoreId);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.scoreMessage(ctx, response)));
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
        UserRef userRef;
        if (nextArg < args.length && (resolver.looksLikeMention(args[nextArg]) || resolver.looksLikeUid(args[nextArg]))) {
            UserRefResolution resolution = resolver.resolveUserRefArgument(args[nextArg]);
            if (resolution.errorMessage() != null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + resolution.errorMessage()));
                return null;
            }
            if (resolution.userRef() == null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + usage));
                return null;
            }
            userRef = resolution.userRef();
            nextArg++;
        } else {
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.NO_BIND));
                return null;
            }
            userRef = new UserRef.ByUid(uid);
        }

        ScoreFilterArguments.ParseResult filters = ScoreFilterArguments.parse(args, nextArg);
        if (filters.isError()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + filters.errorMessage() + "\n" + CommandUsage.SCORE_FILTERS));
            return null;
        }
        return new ScoreListRequest(range, userRef, filters.filters());
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

    public void handleS(Context ctx) {
        var target = history.parseScoreArguments(ctx, CommandUsage.S, 1, arg -> arg.startsWith("+"));
        if (target == null) return;
        String option = target.nextArgument(ctx);
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
            var ids = history.resolve(ctx, SCORE, target, filters, mod);
            history.remember(ctx, ids);
            var response = APIHelper.getScoreResponse(ids.scoreId());
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.scoreMessage(ctx, response)));
        } catch (Exception e) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + TaskCoordinator.resolveErrorMessage(e)
                    + "\n> Tips: 若要查找指定谱面上的成绩，请使用 /s __m__`bid`"));
            org.apache.logging.log4j.LogManager.getLogger(ScoreCommandHandler.class)
                    .error("Failed to execute /s", e);
        }
    }

    public void handleSa(Context ctx) {
        var target = history.parseArguments(ctx, CommandUsage.SA, 0);
        if (target == null) return;
        try (var _ = taskCoordinator.beginRequest(ctx, "Score Analysis")) {
            var ids = history.resolve(ctx, SCORE, target);
            history.remember(ctx, ids);
            String scoreId = ids.scoreId();
            var response = APIHelper.getScoreAnalyzeResponse(scoreId);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.scoreAnalyzeMessage(ctx, response)));
        }
    }

    public void handleMa(Context ctx) {
        var target = history.parseArguments(ctx, CommandUsage.MA, 1, arg -> arg.startsWith("#"));
        if (target == null) return;
        String indexArgument = target.nextArgument(ctx);
        if (indexArgument != null) {
            Integer index = parseMissIndex(indexArgument, target.consumedArgs() == 0);
            if (index == null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.MA));
                return;
            }
            try (var _ = taskCoordinator.beginRequest(ctx, "Miss Visualize")) {
                var ids = history.resolve(ctx, SCORE, target);
                history.remember(ctx, ids);
                String scoreId = ids.scoreId();
                var response = APIHelper.getMissVisualizeResponse(scoreId, index);
                ctx.sendReply(taskCoordinator.imageMessage(response, null));
            }
            return;
        }
        try (var _ = taskCoordinator.beginRequest(ctx, "Get Score Misses")) {
            var ids = history.resolve(ctx, SCORE, target);
            history.remember(ctx, ids);
            String scoreId = ids.scoreId();
            var response = APIHelper.getScoreMissesResponse(scoreId);
            ctx.sendReply(replyFactory.scoreMissesMessage(ctx, response));
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

    record TbArguments(int days, String target) {
    }

    record ScoreListRange(int start, int end) {
    }

    private record ScoreListRequest(ScoreListRange range, UserRef userRef, java.util.List<String> filters) {
    }

}

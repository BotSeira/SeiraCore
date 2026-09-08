package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.target.CommandTargets;
import static xyz.zcraft.seira.command.target.TargetKind.SCORE;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.*;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.UserRef;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class ScoreCommandHandler {
    private static final int MAX_SCORE_LIST_COUNT = 200;

    private final Resolver resolver;
    private final CommandTargets targets;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;

    public ScoreCommandHandler(
            Resolver resolver,
            CommandTargets targets,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory
    ) {
        this.resolver = resolver;
        this.targets = targets;
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

            taskCoordinator.runImageRequest(
                    ctx,
                    "Score",
                    () -> APIHelper.getScoreResponse(targets.resolve(ctx, SCORE, target)),
                    replyFactory::scoreMessage
            );
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

        taskCoordinator.runImageRequest(
                ctx,
                "Best Scores",
                () -> APIHelper.getBoNResponse(request.count(), request.userRef(), request.filters()),
                replyFactory::bpMessage
        );
    }

    public void handleRs(Context ctx, boolean includeFail) {
        if (ctx.args().length == 0) {
            ShortcutTarget target = resolver.parseTarget(ctx.command() + "1", ctx.senderUserId());
            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + target.errorMessage()));
                return;
            }

            taskCoordinator.runImageRequest(
                    ctx,
                    "Score",
                    () -> APIHelper.getScoreResponse(targets.resolve(ctx, SCORE, target)),
                    replyFactory::scoreMessage
            );
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

        taskCoordinator.runImageRequest(
                ctx,
                "Recent Score",
                () -> APIHelper.getRecentResponse(request.count(), request.userRef(), includeFail, request.filters()),
                replyFactory::rsMessage
        );
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
        taskCoordinator.runImageRequest(
                ctx,
                "Recent Best Scores",
                () -> APIHelper.getTodayBestResponse(target, request.days()),
                replyFactory::tbMessage
        );
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
        taskCoordinator.runImageRequest(
                ctx,
                "Score",
                () -> APIHelper.getScoreResponse(targets.resolve(ctx, SCORE, target, filters.filters())),
                replyFactory::scoreMessage
        );
    }

    private ScoreListRequest parseScoreListRequest(Context ctx, String usage) {
        String[] args = ctx.args();
        Integer count = resolver.parsePositiveInt(args[0]);
        if (count == null || count > MAX_SCORE_LIST_COUNT) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + usage + "\n数量必须在 1 到 " + MAX_SCORE_LIST_COUNT + " 之间。"));
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
        return new ScoreListRequest(count, userRef, filters.filters());
    }

    public void handleS(Context ctx) {
        var target = targets.parseScore(ctx, CommandUsage.S);
        if (target == null) return;
        taskCoordinator.runImageRequest(ctx, "Score",
                () -> APIHelper.getScoreResponse(targets.resolve(ctx, target)),
                replyFactory::scoreMessage,
                "> Tips: 若要查找指定谱面上的成绩，请使用 /s __m__`bid`");
    }

    public void handleSa(Context ctx) {
        var target = targets.parse(ctx, SCORE, CommandUsage.SA, 0);
        if (target == null) return;
        taskCoordinator.runImageRequest(ctx, "Score Analysis",
                () -> APIHelper.getScoreAnalyzeResponse(targets.resolve(ctx, target)),
                replyFactory::scoreAnalyzeMessage);
    }

    public void handleMa(Context ctx) {
        var target = targets.parse(ctx, SCORE, CommandUsage.MA, 1, arg -> arg.startsWith("#"));
        if (target == null) return;
        String indexArgument = target.nextArgument(ctx);
        if (indexArgument != null) {
            Integer index = parseMissIndex(indexArgument, target.consumedArgs() == 0);
            if (index == null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.MA));
                return;
            }
            taskCoordinator.runImageRequest(ctx, "Miss Visualize",
                    () -> APIHelper.getMissVisualizeResponse(targets.resolve(ctx, target), index),
                    (_, _) -> null);
            return;
        }
        taskCoordinator.runApiRequest(ctx, "Get Score Misses", () ->
                ctx.sendReply(replyFactory.scoreMissesMessage(ctx, APIHelper.getScoreMissesResponse(targets.resolve(ctx, target)))));
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

    private record ScoreListRequest(int count, UserRef userRef, java.util.List<String> filters) {
    }

}

package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.*;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.ScoreFilterArguments;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.command.parse.ShortcutTarget;
import xyz.zcraft.seira.command.parse.TargetResolution;
import xyz.zcraft.seira.command.parse.UserRefResolution;
import xyz.zcraft.seira.data.UserRef;

public final class ScoreCommandHandler {
    private static final int MAX_SCORE_LIST_COUNT = 200;

    private final Resolver resolver;
    private final TargetHistory targetHistory;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;

    public ScoreCommandHandler(
            Resolver resolver,
            TargetHistory targetHistory,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory
    ) {
        this.resolver = resolver;
        this.targetHistory = targetHistory;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
    }

    public void handleBo(Context ctx) {
        if (ctx.args().length == 0) {
            ShortcutTarget target = resolver.parseTarget("bo1", ctx.senderUserId());
            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
                return;
            }

            taskCoordinator.runImageRequest(
                    ctx,
                    "Score",
                    () -> APIHelper.getScoreResponse(target),
                    replyFactory::scoreMessage
            );
            return;
        }
        if (ScoreFilterArguments.looksLikeFilter(ctx.args()[0])) {
            handleFilteredSingleScore(ctx, "bo");
            return;
        }

        ScoreListRequest request = parseScoreListRequest(ctx, CommandUsage.BO);
        if (request == null) return;

        taskCoordinator.runImageRequest(
                ctx,
                "Best Scores",
                () -> APIHelper.getBoNResponse(request.count(), request.userRef(), request.filters()),
                replyFactory::boMessage
        );
    }

    public void handleRs(Context ctx, boolean includeFail) {
        if (ctx.args().length == 0) {
            ShortcutTarget target = resolver.parseTarget(ctx.command() + "1", ctx.senderUserId());
            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
                return;
            }

            taskCoordinator.runImageRequest(
                    ctx,
                    "Score",
                    () -> APIHelper.getScoreResponse(target),
                    replyFactory::scoreMessage
            );
            return;
        }
        if (ScoreFilterArguments.looksLikeFilter(ctx.args()[0])) {
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
            ctx.sendReply(PendingMessage.ofString(CommandUsage.TB));
            return;
        }

        UserRef userRef;
        if (request.target() != null) {
            UserRefResolution resolution = resolver.resolveUserRefArgument(request.target());
            if (resolution.errorMessage() != null) {
                ctx.sendReply(PendingMessage.ofString(resolution.errorMessage()));
                return;
            }
            if (resolution.userRef() == null) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.TB));
                return;
            }
            userRef = resolution.userRef();
        } else {
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.NO_BIND));
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

    record TbArguments(int days, String target) {
    }

    private void handleFilteredSingleScore(Context ctx, String macroType) {
        ScoreFilterArguments.ParseResult filters = ScoreFilterArguments.parse(ctx.args(), 0);
        if (filters.isError()) {
            ctx.sendReply(PendingMessage.ofString(filters.errorMessage() + "\n" + CommandUsage.SCORE_FILTERS));
            return;
        }

        Long uid = resolver.resolveBoundUid(ctx.senderUserId());
        if (uid == null) {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.NO_BIND));
            return;
        }

        ShortcutTarget target = new ShortcutTarget(null, new UserRef.ByUid(uid), macroType, 1L, null);
        taskCoordinator.runImageRequest(
                ctx,
                "Score",
                () -> APIHelper.getScoreResponse(target, filters.filters()),
                replyFactory::scoreMessage
        );
    }

    private ScoreListRequest parseScoreListRequest(Context ctx, String usage) {
        String[] args = ctx.args();
        Integer count = resolver.parsePositiveInt(args[0]);
        if (count == null || count > MAX_SCORE_LIST_COUNT) {
            ctx.sendReply(PendingMessage.ofString(usage + "\n数量必须在 1 到 " + MAX_SCORE_LIST_COUNT + " 之间。"));
            return null;
        }

        int nextArg = 1;
        UserRef userRef;
        if (nextArg < args.length && resolver.looksLikeMention(args[nextArg])) {
            UserRefResolution resolution = resolver.resolveUserRefArgument(args[nextArg]);
            if (resolution.errorMessage() != null) {
                ctx.sendReply(PendingMessage.ofString(resolution.errorMessage()));
                return null;
            }
            if (resolution.userRef() == null) {
                ctx.sendReply(PendingMessage.ofString(usage));
                return null;
            }
            userRef = resolution.userRef();
            nextArg++;
        } else {
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.NO_BIND));
                return null;
            }
            userRef = new UserRef.ByUid(uid);
        }

        ScoreFilterArguments.ParseResult filters = ScoreFilterArguments.parse(args, nextArg);
        if (filters.isError()) {
            ctx.sendReply(PendingMessage.ofString(filters.errorMessage() + "\n" + CommandUsage.SCORE_FILTERS));
            return null;
        }
        return new ScoreListRequest(count, userRef, filters.filters());
    }

    private record ScoreListRequest(int count, UserRef userRef, java.util.List<String> filters) {
    }

    public void handleS(Context ctx) {
        ShortcutTarget target;
        if (ctx.args().length == 0) {
            target = targetHistory.get(ctx.senderUserId());
        } else if (ctx.args().length <= 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());

            if (ctx.args().length != targetResolution.consumedArgs()) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.S));
                return;
            }

            target = targetResolution.target();

            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
                return;
            }

            targetHistory.put(ctx.senderUserId(), target);
        } else {
            target = null;
        }

        if (target == null) {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.S));
            return;
        }

        taskCoordinator.runImageRequest(
                ctx,
                "Score",
                () -> APIHelper.getScoreResponse(target),
                replyFactory::scoreMessage
        );
    }

    public void handleSa(Context ctx) {
        ShortcutTarget target;
        if (ctx.args().length == 0) {
            target = targetHistory.get(ctx.senderUserId());
        } else if (ctx.args().length <= 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());

            if (ctx.args().length != targetResolution.consumedArgs()) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.SA));
                return;
            }

            target = targetResolution.target();

            if (target.isError()) {
                ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
                return;
            }

            targetHistory.put(ctx.senderUserId(), target);
        } else {
            target = null;
        }

        if (target == null) {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.SA));
            return;
        }

        taskCoordinator.runImageRequest(
                ctx,
                "Score Analysis",
                () -> APIHelper.getScoreAnalyzeResponse(target),
                replyFactory::scoreAnalyzeMessage
        );
    }

    public void handleMa(Context ctx) {
        TargetResolution targetResolution = targetHistory.resolveOptionalTarget(ctx, resolver, arg -> arg.startsWith("#"));
        ShortcutTarget target = targetResolution.target();
        if (target == null) {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.MA));
            return;
        }
        if (target.isError()) {
            ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
            return;
        }

        int remainingArgs = ctx.args().length - targetResolution.consumedArgs();
        if (remainingArgs > 1) {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.MA));
            return;
        }

        if (remainingArgs == 1) {
            Integer index = parseMissIndex(
                    ctx.args()[targetResolution.consumedArgs()],
                    targetResolution.consumedArgs() == 0
            );
            if (index == null) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.MA));
                return;
            }

            targetHistory.rememberExplicitTarget(ctx, targetResolution);

            taskCoordinator.runImageRequest(
                    ctx,
                    "Miss Visualize",
                    () -> APIHelper.getMissVisualizeResponse(target, index),
                    (_, _) -> null
            );
            return;
        }

        targetHistory.rememberExplicitTarget(ctx, targetResolution);

        taskCoordinator.runApiRequest(ctx, "Get Score Misses", () ->
                ctx.sendReply(replyFactory.scoreMissesMessage(ctx, APIHelper.getScoreMissesResponse(target)))
        );
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

}

package xyz.zcraft.seira.command;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.resolution.ShortcutTarget;
import xyz.zcraft.seira.command.resolution.TargetResolution;
import xyz.zcraft.seira.command.resolution.UserRefResolution;
import xyz.zcraft.seira.data.UserRef;

final class ScoreCommandHandler {
    private final Resolver resolver;
    private final TargetHistory targetHistory;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;

    ScoreCommandHandler(
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

    RouteDecision handleBo(Context ctx) {
        if (ctx.args().length == 2) {
            Integer n = resolver.parsePositiveInt(ctx.args()[0]);
            if (n == null) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.BO));
            }
            UserRefResolution userRefResolution = resolver.resolveUserRefArgument(ctx.args()[1]);
            if (userRefResolution.errorMessage() != null) {
                return RouteDecision.sync(PendingMessage.ofString(userRefResolution.errorMessage()));
            }
            if (userRefResolution.userRef() == null) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.BO));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Best Scores",
                    () -> APIHelper.getBoNResponse(n, userRefResolution.userRef()),
                    replyFactory::boMessage
            );
        } else if (ctx.args().length == 1) {
            Integer n = resolver.parsePositiveInt(ctx.args()[0]);
            if (n == null) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.BO));
            }
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.NO_BIND));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Best Scores",
                    () -> APIHelper.getBoNResponse(n, new UserRef.ByUid(uid)),
                    replyFactory::boMessage
            );
        } else if (ctx.args().length == 0) {
            ShortcutTarget target = resolver.parseTarget("bo1", ctx.senderUserId());
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Score",
                    () -> APIHelper.getScoreResponse(target),
                    replyFactory::scoreMessage
            );
        } else {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.BO));
        }
    }

    RouteDecision handleRs(Context ctx, boolean includeFail) {
        if (ctx.args().length == 2) {
            Integer n = resolver.parsePositiveInt(ctx.args()[0]);
            if (n == null) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.RS));
            }

            UserRefResolution userRefResolution = resolver.resolveUserRefArgument(ctx.args()[1]);
            if (userRefResolution.errorMessage() != null) {
                return RouteDecision.sync(PendingMessage.ofString(userRefResolution.errorMessage()));
            }

            if (userRefResolution.userRef() == null) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.RS));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Recent Score",
                    () -> APIHelper.getRecentResponse(n, userRefResolution.userRef(), includeFail),
                    replyFactory::rsMessage
            );
        } else if (ctx.args().length == 1) {
            Integer n = resolver.parsePositiveInt(ctx.args()[0]);
            if (n == null) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.RS));
            }
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.NO_BIND));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Recent Score",
                    () -> APIHelper.getRecentResponse(n, new UserRef.ByUid(uid), includeFail),
                    replyFactory::rsMessage
            );
        } else if (ctx.args().length == 0) {
            ShortcutTarget target = resolver.parseTarget(ctx.command() + "1", ctx.senderUserId());
            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Score",
                    () -> APIHelper.getScoreResponse(target),
                    replyFactory::scoreMessage
            );
        } else {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.RS));
        }
    }

    RouteDecision handleS(Context ctx) {
        ShortcutTarget target;
        if (ctx.args().length == 0) {
            target = targetHistory.get(ctx.senderUserId());
        } else if (ctx.args().length <= 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());

            if (ctx.args().length != targetResolution.consumedArgs()) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.S));
            }

            target = targetResolution.target();

            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            targetHistory.put(ctx.senderUserId(), target);
        } else {
            target = null;
        }

        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.S));
        }

        return taskCoordinator.queueImageRequest(
                ctx,
                "Score",
                () -> APIHelper.getScoreResponse(target),
                replyFactory::scoreMessage
        );
    }

    RouteDecision handleSa(Context ctx) {
        ShortcutTarget target;
        if (ctx.args().length == 0) {
            target = targetHistory.get(ctx.senderUserId());
        } else if (ctx.args().length <= 2) {
            TargetResolution targetResolution = resolver.resolveTargetWithOptionalMention(ctx.args(), ctx.senderUserId());

            if (ctx.args().length != targetResolution.consumedArgs()) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.SA));
            }

            target = targetResolution.target();

            if (target.isError()) {
                return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
            }

            targetHistory.put(ctx.senderUserId(), target);
        } else {
            target = null;
        }

        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.SA));
        }

        return taskCoordinator.queueImageRequest(
                ctx,
                "Score Analysis",
                () -> APIHelper.getScoreAnalyzeResponse(target),
                replyFactory::scoreAnalyzeMessage
        );
    }

    RouteDecision handleMa(Context ctx) {
        TargetResolution targetResolution = targetHistory.resolveOptionalTarget(ctx, resolver, arg -> arg.startsWith("#"));
        ShortcutTarget target = targetResolution.target();
        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.MA));
        }
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        int remainingArgs = ctx.args().length - targetResolution.consumedArgs();
        if (remainingArgs > 1) {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.MA));
        }

        if (remainingArgs == 1) {
            Integer index = parseMissIndex(
                    ctx.args()[targetResolution.consumedArgs()],
                    targetResolution.consumedArgs() == 0
            );
            if (index == null) {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.MA));
            }

            targetHistory.rememberExplicitTarget(ctx, targetResolution);

            return taskCoordinator.queueImageRequest(
                    ctx,
                    "Miss Visualize",
                    () -> APIHelper.getMissVisualizeResponse(target, index),
                    (_, _) -> null
            );
        }

        targetHistory.rememberExplicitTarget(ctx, targetResolution);

        return taskCoordinator.queueApiRequest(
                ctx,
                "Get Score Misses",
                () -> replyFactory.scoreMissesMessage(ctx, APIHelper.getScoreMissesResponse(target))
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

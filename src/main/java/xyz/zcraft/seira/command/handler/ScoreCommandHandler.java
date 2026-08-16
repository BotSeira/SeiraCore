package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.*;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.command.parse.ShortcutTarget;
import xyz.zcraft.seira.command.parse.TargetResolution;
import xyz.zcraft.seira.command.parse.UserRefResolution;
import xyz.zcraft.seira.data.UserRef;

public final class ScoreCommandHandler {
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
        if (ctx.args().length == 2) {
            Integer n = resolver.parsePositiveInt(ctx.args()[0]);
            if (n == null) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.BO));
                return;
            }
            UserRefResolution userRefResolution = resolver.resolveUserRefArgument(ctx.args()[1]);
            if (userRefResolution.errorMessage() != null) {
                ctx.sendReply(PendingMessage.ofString(userRefResolution.errorMessage()));
                return;
            }
            if (userRefResolution.userRef() == null) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.BO));
                return;
            }

            taskCoordinator.runImageRequest(
                    ctx,
                    "Best Scores",
                    () -> APIHelper.getBoNResponse(n, userRefResolution.userRef()),
                    replyFactory::boMessage
            );
        } else if (ctx.args().length == 1) {
            Integer n = resolver.parsePositiveInt(ctx.args()[0]);
            if (n == null) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.BO));
                return;
            }
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.NO_BIND));
                return;
            }

            taskCoordinator.runImageRequest(
                    ctx,
                    "Best Scores",
                    () -> APIHelper.getBoNResponse(n, new UserRef.ByUid(uid)),
                    replyFactory::boMessage
            );
        } else if (ctx.args().length == 0) {
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
        } else {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.BO));
        }
    }

    public void handleRs(Context ctx, boolean includeFail) {
        if (ctx.args().length == 2) {
            Integer n = resolver.parsePositiveInt(ctx.args()[0]);
            if (n == null) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.RS));
                return;
            }

            UserRefResolution userRefResolution = resolver.resolveUserRefArgument(ctx.args()[1]);
            if (userRefResolution.errorMessage() != null) {
                ctx.sendReply(PendingMessage.ofString(userRefResolution.errorMessage()));
                return;
            }

            if (userRefResolution.userRef() == null) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.RS));
                return;
            }

            taskCoordinator.runImageRequest(
                    ctx,
                    "Recent Score",
                    () -> APIHelper.getRecentResponse(n, userRefResolution.userRef(), includeFail),
                    replyFactory::rsMessage
            );
        } else if (ctx.args().length == 1) {
            Integer n = resolver.parsePositiveInt(ctx.args()[0]);
            if (n == null) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.RS));
                return;
            }
            Long uid = resolver.resolveBoundUid(ctx.senderUserId());
            if (uid == null) {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.NO_BIND));
                return;
            }

            taskCoordinator.runImageRequest(
                    ctx,
                    "Recent Score",
                    () -> APIHelper.getRecentResponse(n, new UserRef.ByUid(uid), includeFail),
                    replyFactory::rsMessage
            );
        } else if (ctx.args().length == 0) {
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
        } else {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.RS));
        }
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

package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.VideoRenderRecord;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.*;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.command.parse.RscTarget;
import xyz.zcraft.seira.command.parse.ShortcutTarget;
import xyz.zcraft.seira.command.parse.TargetResolution;
import xyz.zcraft.seira.command.route.RouteDecision;
import xyz.zcraft.seira.util.TimeDurationParser;

import java.util.function.Function;

public final class ReplayCommandHandler {
    private final Resolver resolver;
    private final TargetHistory targetHistory;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final VideoRenderRecord videoRenderRecord;
    private final ReplayResultStore replayResults;
    private final Function<String, String> accessTokenProvider;

    public ReplayCommandHandler(
            Resolver resolver,
            TargetHistory targetHistory,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            VideoRenderRecord videoRenderRecord,
            ReplayResultStore replayResults,
            Function<String, String> accessTokenProvider
    ) {
        this.resolver = resolver;
        this.targetHistory = targetHistory;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.videoRenderRecord = videoRenderRecord;
        this.replayResults = replayResults;
        this.accessTokenProvider = accessTokenProvider;
    }

    public RouteDecision handleR(Context ctx) {
        TargetResolution targetResolution = targetHistory.resolveOptionalTarget(ctx, resolver, TimeDurationParser::isTimeRange);
        if (ctx.args().length - targetResolution.consumedArgs() > 1) {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.R));
        }

        ShortcutTarget target = targetResolution.target();
        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.R));
        }
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        TimeDurationParser.TimeRange range = null;

        if (ctx.args().length > targetResolution.consumedArgs()) {
            try {
                range = TimeDurationParser.parseRange(ctx.args()[targetResolution.consumedArgs()]);
            } catch (IllegalArgumentException e) {
                return RouteDecision.sync(PendingMessage.ofString("无法解析时间范围"));
            }
        }

        targetHistory.rememberExplicitTarget(ctx, targetResolution);

        TimeDurationParser.TimeRange finalRange = range;
        return taskCoordinator.queueReplayTask(
                ctx,
                "Score Render",
                qqUpload -> {
                    APIHelper.ReplayTaskInfo task = APIHelper.createReplayRenderTask(target, finalRange, qqUpload);
                    videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
                    return task;
                },
                replyFactory::replayMessage);
    }

    public RouteDecision handleRsc(Context ctx) {
        if (ctx.groupId() == null || ctx.groupId().isBlank()) {
            return RouteDecision.sync(PendingMessage.ofString("/rsc 仅支持群聊使用。"));
        }

        TargetResolution targetResolution = targetHistory.resolveOptionalTarget(
                ctx,
                resolver,
                arg -> arg.startsWith("+") || TimeDurationParser.isTimeRange(arg)
        );
        ShortcutTarget target = targetResolution.target();
        if (target == null) {
            return RouteDecision.sync(PendingMessage.ofString(CommandUsage.RSC));
        }
        if (target.isError()) {
            return RouteDecision.sync(PendingMessage.ofString(target.errorMessage()));
        }

        String extraUidArg = null;

        for (int i = targetResolution.consumedArgs(); i < ctx.args().length; i++) {
            if (ctx.args()[i].startsWith("+") || ctx.args()[i].startsWith("=")) {
                if (extraUidArg != null) {
                    return RouteDecision.sync(PendingMessage.ofString(CommandUsage.RSC));
                }
                extraUidArg = ctx.args()[i];
            } else {
                return RouteDecision.sync(PendingMessage.ofString(CommandUsage.RSC));
            }
        }

        RscTarget rscTarget = resolver.resolveRscTarget(ctx.groupId(), extraUidArg);
        if (rscTarget.errorMessage() != null) {
            return RouteDecision.sync(PendingMessage.ofString(rscTarget.errorMessage()));
        }

        String[] uidArray = rscTarget.uids();

        targetHistory.rememberExplicitTarget(ctx, targetResolution);

        return taskCoordinator.queueReplayTask(
                ctx,
                "Showcase Render",
                qqUpload -> {
                    var task = APIHelper.createReplayShowcaseTask(
                            target, uidArray, accessTokenProvider.apply(ctx.senderUserId()), qqUpload);
                    videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
                    return task;
                },
                replyFactory::replayMessage);
    }

    public RouteDecision handleRstat(Context ctx) {
        if (ctx.args().length != 1 && ctx.args().length != 0) {
            return RouteDecision.sync(PendingMessage.ofString("用法：/rstat [任务ID]"));
        }

        String jobId;
        if (ctx.args().length == 0) {
            if (videoRenderRecord.hasRenderTask(ctx.senderUserId())) {
                jobId = videoRenderRecord.getRenderTask(ctx.senderUserId());
            } else {
                return RouteDecision.sync(PendingMessage.ofString("未找到渲染请求"));
            }
        } else {
            jobId = ctx.args()[0];
        }

        APIHelper.ReplayRenderResult replayResult = replayResults.get(jobId);
        if (replayResult != null) {
            PendingMessage video = replayResult.qqFile() != null
                    ? PendingMessage.ofUploadedVideo(replayResult.qqFile())
                    : PendingMessage.ofVideoUrl(replayResult.videoUrl());
            return RouteDecision.sync(video, b -> {
                if (b) {
                    replayResults.remove(jobId);
                }
            });
        }

        return RouteDecision.sync(replyFactory.replayStatMessage(ctx, jobId, APIHelper.getRenderStat(jobId)));
    }

}

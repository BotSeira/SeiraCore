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

    public void handleR(Context ctx) {
        TargetResolution targetResolution = targetHistory.resolveOptionalTarget(ctx, resolver, TimeDurationParser::isTimeRange);
        if (ctx.args().length - targetResolution.consumedArgs() > 1) {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.R));
            return;
        }

        ShortcutTarget target = targetResolution.target();
        if (target == null) {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.R));
            return;
        }
        if (target.isError()) {
            ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
            return;
        }

        TimeDurationParser.TimeRange range = null;

        if (ctx.args().length > targetResolution.consumedArgs()) {
            try {
                range = TimeDurationParser.parseRange(ctx.args()[targetResolution.consumedArgs()]);
            } catch (IllegalArgumentException e) {
                ctx.sendReply(PendingMessage.ofString("无法解析时间范围"));
                return;
            }
        }

        targetHistory.rememberExplicitTarget(ctx, targetResolution);

        TimeDurationParser.TimeRange finalRange = range;
        taskCoordinator.runReplayRequest(
                ctx,
                "Score Render",
                qqUpload -> {
                    APIHelper.ReplayTaskInfo task = APIHelper.createReplayRenderTask(target, finalRange, qqUpload);
                    videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
                    return task;
                },
                replyFactory::replayMessage);
    }

    public void handleRsc(Context ctx) {
        if (ctx.groupId() == null || ctx.groupId().isBlank()) {
            ctx.sendReply(PendingMessage.ofString("/rsc 仅支持群聊使用。"));
            return;
        }

        TargetResolution targetResolution = targetHistory.resolveOptionalTarget(
                ctx,
                resolver,
                arg -> arg.startsWith("+") || arg.startsWith("=")
        );
        ShortcutTarget target = targetResolution.target();
        if (target == null) {
            ctx.sendReply(PendingMessage.ofString(CommandUsage.RSC));
            return;
        }
        if (target.isError()) {
            ctx.sendReply(PendingMessage.ofString(target.errorMessage()));
            return;
        }

        String extraUidArg = null;

        int i = targetResolution.consumedArgs();

        if (i < ctx.args().length) {
            if (ctx.args()[i].startsWith("+") || ctx.args()[i].startsWith("=")) {
                extraUidArg = ctx.query().substring(Math.max(ctx.query().indexOf("+"), ctx.query().indexOf("=")));
            } else {
                ctx.sendReply(PendingMessage.ofString(CommandUsage.RSC));
                return;
            }
        }

        RscTarget rscTarget = target.isLocalScore() && extraUidArg == null
                ? new RscTarget(new String[0], null)
                : resolver.resolveRscTarget(ctx.groupId(), extraUidArg);
        if (rscTarget.errorMessage() != null) {
            ctx.sendReply(PendingMessage.ofString(rscTarget.errorMessage()));
            return;
        }

        String[] targetsArray = rscTarget.targets();

        targetHistory.rememberExplicitTarget(ctx, targetResolution);

        taskCoordinator.runReplayRequest(
                ctx,
                "Showcase Render",
                qqUpload -> {
                    var task = APIHelper.createReplayShowcaseTask(
                            target, targetsArray, accessTokenProvider.apply(ctx.senderUserId()), qqUpload);
                    videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
                    return task;
                },
                replyFactory::replayMessage);
    }

    public void handleRstat(Context ctx) {
        if (ctx.args().length != 1 && ctx.args().length != 0) {
            ctx.sendReply(PendingMessage.ofString("用法：/rstat [任务ID]"));
            return;
        }

        String jobId;
        if (ctx.args().length == 0) {
            if (videoRenderRecord.hasRenderTask(ctx.senderUserId())) {
                jobId = videoRenderRecord.getRenderTask(ctx.senderUserId());
            } else {
                ctx.sendReply(PendingMessage.ofString("未找到渲染请求"));
                return;
            }
        } else {
            jobId = ctx.args()[0];
        }

        APIHelper.ReplayRenderResult replayResult = replayResults.get(jobId);
        if (replayResult != null) {
            PendingMessage video = replayResult.qqFile() != null
                    ? PendingMessage.ofUploadedVideo(replayResult.qqFile(), replayResult.videoUrl())
                    : PendingMessage.ofVideoUrl(replayResult.videoUrl());
            if (ctx.sendReply(video).success()) {
                replayResults.remove(jobId);
            }
            return;
        }

        ctx.sendReply(replyFactory.replayStatMessage(ctx, jobId, APIHelper.getRenderStat(jobId)));
    }

}

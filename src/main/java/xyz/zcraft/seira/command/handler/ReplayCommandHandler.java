package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.VideoRenderRecord;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.ReplayResultStore;
import xyz.zcraft.seira.command.TargetHistory;
import static xyz.zcraft.seira.command.TargetHistory.Type.*;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.RscTarget;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.util.TimeDurationParser;

import java.util.function.Function;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class ReplayCommandHandler {
    private final Resolver resolver;
    private final TargetHistory history;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final VideoRenderRecord videoRenderRecord;
    private final ReplayResultStore replayResults;
    private final Function<String, String> accessTokenProvider;

    public ReplayCommandHandler(
            Resolver resolver,
            TargetHistory history,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            VideoRenderRecord videoRenderRecord,
            ReplayResultStore replayResults,
            Function<String, String> accessTokenProvider
    ) {
        this.resolver = resolver;
        this.history = history;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.videoRenderRecord = videoRenderRecord;
        this.replayResults = replayResults;
        this.accessTokenProvider = accessTokenProvider;
    }

    public void handleR(Context ctx) {
        var target = history.parseArguments(ctx, CommandUsage.R, 1, TimeDurationParser::isTimeRange);
        if (target == null) return;

        TimeDurationParser.TimeRange range = null;

        if (ctx.args().length > target.consumedArgs()) {
            try {
                range = TimeDurationParser.parseRange(ctx.args()[target.consumedArgs()]);
            } catch (IllegalArgumentException e) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "无法解析时间范围"));
                return;
            }
        }

        TimeDurationParser.TimeRange finalRange = range;
        taskCoordinator.runReplayRequest(
                ctx,
                "Score Render",
                qqUpload -> {
                    APIHelper.ReplayTaskInfo task = APIHelper.createReplayRenderTask(history.resolveAndGet(ctx, SCORE, target), finalRange, qqUpload);
                    videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
                    return task;
                },
                replyFactory::replayMessage);
    }

    public void handleRsc(Context ctx) {
        if (ctx.groupId() == null || ctx.groupId().isBlank()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "/rsc 仅支持群聊使用。"));
            return;
        }

        var target = history.parseArguments(ctx, CommandUsage.RSC, Integer.MAX_VALUE,
                arg -> arg.startsWith("+") || arg.startsWith("="));
        if (target == null) return;

        String extraUidArg = null;

        int i = target.consumedArgs();

        if (i < ctx.args().length) {
            if (ctx.args()[i].startsWith("+") || ctx.args()[i].startsWith("=")) {
                extraUidArg = ctx.query().substring(Math.max(ctx.query().indexOf("+"), ctx.query().indexOf("=")));
            } else {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.RSC));
                return;
            }
        }

        RscTarget rscTarget = history.isLocalScore(ctx, target) && extraUidArg == null
                ? new RscTarget(new String[0], null)
                : resolver.resolveRscTarget(ctx.groupId(), extraUidArg);
        if (rscTarget.errorMessage() != null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + rscTarget.errorMessage()));
            return;
        }

        String[] targetsArray = rscTarget.targets();

        taskCoordinator.runReplayRequest(
                ctx,
                "Showcase Render",
                qqUpload -> {
                    var task = APIHelper.createReplayShowcaseTask(
                            history.resolveAndGet(ctx, history.isLocalScore(ctx, target) ? SCORE : BEATMAP, target), targetsArray, accessTokenProvider.apply(ctx.senderUserId()), qqUpload);
                    videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
                    return task;
                },
                replyFactory::replayMessage);
    }

    public void handleRstat(Context ctx) {
        if (ctx.args().length != 1 && ctx.args().length != 0) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "用法：/rstat [任务ID]"));
            return;
        }

        String jobId;
        if (ctx.args().length == 0) {
            if (videoRenderRecord.hasRenderTask(ctx.senderUserId())) {
                jobId = videoRenderRecord.getRenderTask(ctx.senderUserId());
            } else {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "未找到渲染请求"));
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

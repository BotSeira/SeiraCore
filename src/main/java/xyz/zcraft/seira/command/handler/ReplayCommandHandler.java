package xyz.zcraft.seira.command.handler;

import org.jline.utils.Log;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.VideoRenderRecord;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.ReplayResultStore;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.TargetLookup;
import xyz.zcraft.seira.command.parse.TargetArguments;
import xyz.zcraft.seira.data.UserRef;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.SendResult;
import xyz.zcraft.seira.util.TimeDurationParser;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class ReplayCommandHandler {
    private final Resolver resolver;
    private final TargetHistory history;
    private final TargetLookup targetLookup;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final VideoRenderRecord videoRenderRecord;
    private final ReplayResultStore replayResults;
    private final Predicate<String> adminAuthorizer;

    public ReplayCommandHandler(
            Resolver resolver,
            TargetHistory history,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            VideoRenderRecord videoRenderRecord,
            ReplayResultStore replayResults,
            Predicate<String> adminAuthorizer,
            java.util.function.Function<String, String> accessTokenProvider
    ) {
        this.resolver = resolver;
        this.history = history;
        this.targetLookup = new TargetLookup(resolver, accessTokenProvider);
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.videoRenderRecord = videoRenderRecord;
        this.replayResults = replayResults;
        this.adminAuthorizer = adminAuthorizer;
    }

    public void handleR(Context ctx) {
        var target = TargetArguments.parse(ctx, resolver, history.get(ctx), CommandUsage.R, 1, TimeDurationParser::isTimeRange);
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

        try (var _ = taskCoordinator.beginRequest(ctx, "Score Render")) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "正在获取谱面以及回放文件，请稍作等待喵..."));
            var resolvedTarget = targetLookup.score(ctx, target.target(), history.get(ctx));
            history.remember(ctx, resolvedTarget);
            String scoreId = resolvedTarget.scoreId();
            var upload = taskCoordinator.createVideoUploadRequest(ctx);
            var task = APIHelper.createReplayRenderTask(scoreId, range, upload);
            videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
            ctx.sendReply(replyFactory.replayMessage(ctx, task));

            APIHelper.ReplayRenderResult result;

            try {
                result = taskCoordinator.waitForReplay(task);
            } catch (Exception e) {
                Log.error("Error while waiting for replay", e);
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + e.getMessage()));
                return;
            }

            SendResult sendResult = ctx.sendReply(taskCoordinator.replayVideoMessage(result));

            if (!sendResult.success()) {
                sendResult = ctx.sendMessage(taskCoordinator.replayVideoMessage(result));
            }

            if (sendResult.success()) {
                replayResults.remove(task.taskId());
            }
        }
    }

    public void handleRsc(Context ctx) {
        if (ctx.groupId() == null || ctx.groupId().isBlank()) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "/rsc 仅支持群聊使用。"));
            return;
        }

        var target = TargetArguments.parse(ctx, resolver, history.get(ctx), CommandUsage.RSC, Integer.MAX_VALUE, arg -> arg.startsWith("+") || arg.startsWith("="));
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

        var remembered = history.get(ctx);
        String localScoreId = target.target() != null
                ? target.target().localScoreId()
                : remembered == null ? null : remembered.scoreId();
        boolean localScore = localScoreId != null && localScoreId.startsWith("loc");
        var participants = new java.util.LinkedHashSet<String>();
        if (!(localScore && extraUidArg == null)) {
            if (extraUidArg == null || extraUidArg.trim().startsWith("+")) {
                var groupUids = xyz.zcraft.seira.db.UserDataStore.findBoundUidsByGroup(ctx.groupId());
                if (groupUids.isEmpty()) {
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "本群还没有已绑定的玩家，请先使用 /bind"));
                    return;
                }
                groupUids.stream().map(String::valueOf).forEach(participants::add);
            }
            if (extraUidArg != null) {
                String body = extraUidArg.trim().substring(1).trim();
                if (body.isEmpty()) {
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "追加ID列表不能为空。"));
                    return;
                }
                for (String token : body.split(",")) {
                    if (token.trim().matches("[us]?[0-9]+")) {
                        participants.add(token.trim());
                    } else if (resolver.looksLikeMention(token)) {
                        var participant = resolver.resolveUserRefArgument(token);
                        if (participant.errorMessage() != null) {
                            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "解析 " + token + " 时出错:" + participant.errorMessage()));
                            return;
                        }
                        if (participant.userRef() instanceof UserRef.ByUid ref) participants.add("u" + ref.getUid());
                        else if (participant.userRef() instanceof UserRef.ByUsername ref) participants.add("@" + ref.getUsername());
                    } else {
                        ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "追加ID列表包含非法值。"));
                        return;
                    }
                }
            }
        }

        try (var _ = taskCoordinator.beginRequest(ctx, "Showcase Render")) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "正在获取谱面以及回放文件，请稍作等待喵..."));
            var resolvedTarget = targetLookup.beatmap(ctx, target.target(), history.get(ctx));
            history.remember(ctx, resolvedTarget);
            long beatmapId = resolvedTarget.beatmapId();
            var upload = taskCoordinator.createVideoUploadRequest(ctx);
            String[] scoreTargets = participants.toArray(String[]::new);
            if (localScore) {
                var ids = new java.util.LinkedHashSet<String>();
                ids.add("s" + localScoreId);
                java.util.Collections.addAll(ids, scoreTargets);
                scoreTargets = ids.toArray(String[]::new);

            }
            var task = APIHelper.createReplayShowcaseTask(beatmapId, scoreTargets, upload);
            videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
            ctx.sendReply(replyFactory.replayMessage(ctx, task));

            APIHelper.ReplayRenderResult result;

            try {
                result = taskCoordinator.waitForReplay(task);
            } catch (Exception e) {
                Log.error("Error while waiting for replay", e);
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + e.getMessage()));
                return;
            }

            SendResult sendResult = ctx.sendReply(taskCoordinator.replayVideoMessage(result));

            if (!sendResult.success()) {
                sendResult = ctx.sendMessage(taskCoordinator.replayVideoMessage(result));
            }

            if (sendResult.success()) {
                replayResults.remove(task.taskId());
            }
        }
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

    public void handleRcancel(Context ctx) {
        if (ctx.args().length != 1) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.RCANCEL));
            return;
        }

        String jobId = ctx.args()[0];
        try {
            if (!UUID.fromString(jobId).toString().equalsIgnoreCase(jobId)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
        } catch (IllegalArgumentException e) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "渲染任务 ID 格式无效。\n" + CommandUsage.RCANCEL));
            return;
        }

        final String owner = videoRenderRecord.getTaskOwner(jobId);
        if (owner != null) {
            if (!owner.equalsIgnoreCase(ctx.senderUserId())
                    && !adminAuthorizer.test(ctx.senderUserId())) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "你无权取消此任务喵。"));
                return;
            }
        }

        var result = APIHelper.cancelReplayRender(jobId);
        String status = Objects.toString(result.getStatus(), "unknown").toLowerCase();
        String message = switch (status) {
            case "canceled" -> "回放渲染已取消。";
            case "done" -> "该回放已经渲染完成，无法取消。";
            case "failed" -> "该回放渲染已经失败，无需取消。";
            case "timeout" -> "该回放渲染已经超时，无需取消。";
            default -> "该回放当前状态为 `" + status + "`，无法取消。";
        };
        if ("canceled".equals(status)) {
            replayResults.remove(jobId);
            videoRenderRecord.removeRenderTask(ctx.senderUserId(), jobId);
        }
        ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + message));
    }
}

package xyz.zcraft.seira.command.handler;

import org.jline.utils.Log;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.VideoRenderRecord;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.parse.TargetInput;
import xyz.zcraft.seira.command.ReplayResultStore;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.SendResult;
import xyz.zcraft.seira.util.TimeDurationParser;

import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class ReplayCommandHandler {
    private final java.util.function.Function<String, String> accessTokenProvider;
    private final Resolver resolver;
    private final TargetHistory history;
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
        this.accessTokenProvider = accessTokenProvider;
        this.history = history;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.videoRenderRecord = videoRenderRecord;
        this.replayResults = replayResults;
        this.adminAuthorizer = adminAuthorizer;
    }

    public void handleR(Context ctx) {
        var target = ctx.argumentCount() == 0 || TimeDurationParser.isTimeRange(ctx.argument(0))
                ? TargetInput.memory() : TargetInput.read(ctx.args());
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - target.consumedArgs() > 1) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.R));
            return;
        }

        TimeDurationParser.TimeRange range = null;

        if (ctx.args().length > target.consumedArgs()) {
            try {
                range = TimeDurationParser.parseRange(ctx.args()[target.consumedArgs()]);
            } catch (IllegalArgumentException e) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "无法解析时间范围"));
                return;
            }
        }

        try (var _ = taskCoordinator.beginRequest(ctx, "Score Render", false)) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "正在获取谱面以及回放文件，请稍作等待喵..."));
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
                    long uid = APIHelper.resolveUid(player);
                    scoreId = APIHelper.lookupBeatmapsetScore(beatmapsetId, target.index(), uid, List.of(), null);
                }
                case RS, RP, BP -> {
                    String player = resolver.player(target.player(), ctx.senderUserId());
                    long uid = APIHelper.resolveUid(player);
                    scoreId = APIHelper.lookupPlayerScore(uid, target.scoreList(), target.index(), List.of(), null);
                }
                case MP -> beatmapId = APIHelper.lookupMultiplayerBeatmap(accessTokenProvider.apply(ctx.senderUserId()));
                case MEMORY -> {}
            }
            if (scoreId == null) {
                if (beatmapId == null) throw new ResolutionException("请指定指令目标谱面喵");
                String player = resolver.player(target.player(), ctx.senderUserId());
                long uid = APIHelper.resolveUid(player);
                scoreId = APIHelper.lookupBeatmapScore(beatmapId, uid, List.of(), null);
            }
            var upload = taskCoordinator.createVideoUploadRequest(ctx);
            var task = APIHelper.createReplayRenderTask(scoreId, range, upload);
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
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

        var target = ctx.argumentCount() == 0 || ctx.argument(0).startsWith("+") || ctx.argument(0).startsWith("=")
                ? TargetInput.memory() : TargetInput.read(ctx.args());
        var remembered = history.get(ctx);
        if (target.kind() == TargetInput.Kind.MEMORY && remembered == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.RSC));
            return;
        }

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

        String localScoreId = target.kind() != TargetInput.Kind.MEMORY
                ? target.kind() == TargetInput.Kind.SCORE ? target.id() : null
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
                        String player = resolver.player(token, ctx.senderUserId());
                        long uid = APIHelper.resolveUid(player);
                        participants.add("u" + uid);
                    } else {
                        ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "追加ID列表包含非法值。"));
                        return;
                    }
                }
            }
        }

        try (var _ = taskCoordinator.beginRequest(ctx, "Showcase Render", false)) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "正在获取谱面以及回放文件，请稍作等待喵..."));
            var previous = target.kind() == TargetInput.Kind.MEMORY ? remembered : null;
            Long beatmapId = previous == null ? null : previous.beatmapId();
            Long beatmapsetId = previous == null ? null : previous.beatmapsetId();
            String scoreId = previous == null ? null : previous.scoreId();
            switch (target.kind()) {
                case ID, MAP -> beatmapId = Long.parseLong(target.id());
                case SCORE -> scoreId = target.id();
                case SET -> {
                    beatmapsetId = Long.parseLong(target.id());
                    beatmapId = APIHelper.lookupBeatmapInSet(beatmapsetId, target.index(), accessTokenProvider.apply(ctx.senderUserId()));
                }
                case RS, RP, BP -> {
                    String player = resolver.player(target.player(), ctx.senderUserId());
                    long uid = APIHelper.resolveUid(player);
                    scoreId = APIHelper.lookupPlayerScore(uid, target.scoreList(), target.index(), List.of(), null);
                }
                case MP -> beatmapId = APIHelper.lookupMultiplayerBeatmap(accessTokenProvider.apply(ctx.senderUserId()));
                case MEMORY -> {}
            }
            if (beatmapId == null && scoreId != null) beatmapId = APIHelper.getScoreBeatmapId(scoreId);
            if (beatmapId == null) throw new ResolutionException("请指定指令目标谱面喵");
            var upload = taskCoordinator.createVideoUploadRequest(ctx);
            String[] scoreTargets = participants.toArray(String[]::new);
            if (localScore) {
                var ids = new java.util.LinkedHashSet<String>();
                ids.add("s" + localScoreId);
                java.util.Collections.addAll(ids, scoreTargets);
                scoreTargets = ids.toArray(String[]::new);

            }
            var task = APIHelper.createReplayShowcaseTask(beatmapId, scoreTargets, upload);
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
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

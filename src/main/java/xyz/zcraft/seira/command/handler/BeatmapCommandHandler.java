package xyz.zcraft.seira.command.handler;

import org.jline.utils.Log;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.api.data.SearchQuery;
import xyz.zcraft.seira.api.data.SearchResultItem;
import xyz.zcraft.seira.api.data.VideoRenderRecord;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.command.TargetLookup;
import xyz.zcraft.seira.command.parse.TargetArguments;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;
import xyz.zcraft.seira.data.SendResult;
import xyz.zcraft.seira.util.TimeDurationParser;

import java.util.List;
import java.util.function.Function;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class BeatmapCommandHandler {
    private final Resolver resolver;
    private final TargetHistory history;
    private final TargetLookup targetLookup;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final VideoRenderRecord videoRenderRecord;
    private final Function<String, String> accessTokenProvider;

    public BeatmapCommandHandler(
            Resolver resolver,
            TargetHistory history,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            VideoRenderRecord videoRenderRecord,
            Function<String, String> accessTokenProvider
    ) {
        this.resolver = resolver;
        this.history = history;
        this.targetLookup = new TargetLookup(resolver, accessTokenProvider);
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.videoRenderRecord = videoRenderRecord;
        this.accessTokenProvider = accessTokenProvider;
    }

    public void handleDaily(Context ctx) {
        try (var timing = taskCoordinator.beginRequest(ctx, "Daily Challenge")) {
            var daily = APIHelper.getDaily();
            ctx.sendReply(PendingMessage.ofMarkdownRaw(daily));
        }
    }

    public void handleM(Context ctx) {
        var target = TargetArguments.parse(ctx, resolver, history.get(ctx), CommandUsage.M, 1);
        if (target == null) return;
        try (var _ = taskCoordinator.beginRequest(ctx, "Beatmap")) {
            var resolvedTarget = targetLookup.beatmap(ctx, target.target(), history.get(ctx));
            history.remember(ctx, resolvedTarget);
            long beatmapId = resolvedTarget.beatmapId();
            var response = APIHelper.getBeatmapResponse(beatmapId, (ctx.argumentCount() > target.consumedArgs() ? ctx.argument(target.consumedArgs()) : null));
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.beatmapMessage(ctx, response)));
        }
    }

    public void handleBma(Context ctx) {
        var target = TargetArguments.parse(ctx, resolver, history.get(ctx), CommandUsage.BMA, 1);
        if (target == null) return;
        try (var timing = taskCoordinator.beginRequest(ctx, "Beatmap Analysis")) {
            var resolvedTarget = targetLookup.beatmap(ctx, target.target(), history.get(ctx));
            history.remember(ctx, resolvedTarget);
            long beatmapId = resolvedTarget.beatmapId();
            var response = APIHelper.getBeatmapAnalysisResponse(beatmapId, (ctx.argumentCount() > target.consumedArgs() ? ctx.argument(target.consumedArgs()) : null));
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.beatmapMessage(ctx, response)));
        }
    }

    public void handleAp(Context ctx) {
        var target = TargetArguments.parse(ctx, resolver, history.get(ctx), CommandUsage.AP, Integer.MAX_VALUE);
        if (target == null) return;
        try (var timing = taskCoordinator.beginRequest(ctx, "Audio Preview")) {
            var resolvedTarget = targetLookup.beatmapset(ctx, target.target(), history.get(ctx));
            history.remember(ctx, resolvedTarget);
            long beatmapsetId = resolvedTarget.beatmapsetId();
            long id = beatmapsetId;
            ctx.sendReply(PendingMessage.ofVoiceUrl("https://b.ppy.sh/preview/" + id + ".mp3").doUpload(false));
        }
    }

    public void handleBpv(Context ctx) {
        var target = TargetArguments.parse(ctx, resolver, history.get(ctx), CommandUsage.BPV, 2, arg -> arg.startsWith("+") || TimeDurationParser.isTimeRange(arg));
        if (target == null) return;

        TimeDurationParser.TimeRange range = null;
        String mods = null;
        for (int optionIndex = target.consumedArgs(); optionIndex < ctx.argumentCount(); optionIndex++) {
            String option = ctx.argument(optionIndex);
            if (TimeDurationParser.isTimeRange(option) && range == null) {
                try {
                    range = TimeDurationParser.parseRange(option);
                } catch (IllegalArgumentException e) {
                    ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "无法解析时间范围"));
                    return;
                }
            } else if (mods == null && !TimeDurationParser.isTimeRange(option)) {
                mods = option;
            } else {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.BPV));
                return;
            }
        }

        try (var _ = taskCoordinator.beginRequest(ctx, "Beatmap Preview Render")) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "正在获取谱面以及回放文件，请稍作等待喵..."));
            var qqUpload = taskCoordinator.createVideoUploadRequest(ctx);
            var resolvedTarget = targetLookup.beatmap(ctx, target.target(), history.get(ctx));
            history.remember(ctx, resolvedTarget);
            long beatmapId = resolvedTarget.beatmapId();
            var task = APIHelper.createBeatmapPreviewTask(beatmapId, mods, range, qqUpload);
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
                taskCoordinator.removeReplayResult(task.taskId());
            }
        }
    }

    public void handleBgp(Context ctx) {
        var target = TargetArguments.parse(ctx, resolver, history.get(ctx), CommandUsage.BGP, Integer.MAX_VALUE);
        if (target == null) return;
        try (var timing = taskCoordinator.beginRequest(ctx, "Background Preview")) {
            var resolvedTarget = targetLookup.beatmap(ctx, target.target(), history.get(ctx));
            history.remember(ctx, resolvedTarget);
            long beatmapId = resolvedTarget.beatmapId();
            var response = APIHelper.getBeatmapBgResponse(beatmapId);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.bgpMessage(ctx, response)));
        }
    }

    public void handleDl(Context ctx) {
        var target = TargetArguments.parse(ctx, resolver, history.get(ctx), CommandUsage.DL, 0);
        if (target == null) return;
        try (var timing = taskCoordinator.beginRequest(ctx, "Download Beatmap")) {
            var resolvedTarget = targetLookup.beatmapset(ctx, target.target(), history.get(ctx));
            history.remember(ctx, resolvedTarget);
            long beatmapsetId = resolvedTarget.beatmapsetId();
            var response = APIHelper.getLookupBeatmapsetResponse(beatmapsetId, accessTokenProvider.apply(ctx.senderUserId()));
            ctx.sendReply(replyFactory.dlMessage(ctx, response));
        }
    }

    public void handleMs(Context ctx) {
        var target = TargetArguments.parse(ctx, resolver, history.get(ctx), "用法：/ms <谱面集ID 或 快捷查询>", 0);
        if (target == null) return;
        try (var timing = taskCoordinator.beginRequest(ctx, "Beatmapset")) {
            var resolvedTarget = targetLookup.beatmapset(ctx, target.target(), history.get(ctx));
            history.remember(ctx, resolvedTarget);
            long beatmapsetId = resolvedTarget.beatmapsetId();
            var response = APIHelper.getBeatmapsetResponse(beatmapsetId);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.beatmapsetMessage(ctx, response)));
        }
    }

    public void handleSms(Context ctx) {
        final SearchQuery searchQuery = resolver.resolveSearchQuery(ctx.query());
        if (searchQuery == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "用法：/sms [#页数] <搜索关键字>"));
            return;
        }
        try (var timing = taskCoordinator.beginRequest(ctx, "Search Beatmapset")) {
            Response<List<SearchResultItem>> searchResponse = APIHelper.searchBeatmapSetResponse(searchQuery);
            ctx.sendReply(replyFactory.searchMessage(ctx, searchResponse, searchQuery));
        }
    }

}

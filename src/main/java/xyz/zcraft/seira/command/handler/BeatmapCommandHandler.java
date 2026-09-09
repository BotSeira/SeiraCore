package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.api.data.SearchQuery;
import xyz.zcraft.seira.api.data.SearchResultItem;
import xyz.zcraft.seira.api.data.VideoRenderRecord;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.TargetHistory;
import static xyz.zcraft.seira.command.TargetHistory.Type.*;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;

import java.util.List;
import java.util.function.Function;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class BeatmapCommandHandler {
    private final Resolver resolver;
    private final TargetHistory history;
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
        var target = history.parseArguments(ctx, CommandUsage.M, 1);
        if (target == null) return;
        try (var timing = taskCoordinator.beginRequest(ctx, "Beatmap")) {
            var ids = history.resolve(ctx, BEATMAP, target);
            history.remember(ctx, ids);
            var response = APIHelper.getBeatmapResponse(ids.beatmapId(), target.nextArgument(ctx));
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.beatmapMessage(ctx, response)));
        }
    }

    public void handleBma(Context ctx) {
        var target = history.parseArguments(ctx, CommandUsage.BMA, 1);
        if (target == null) return;
        try (var timing = taskCoordinator.beginRequest(ctx, "Beatmap Analysis")) {
            var ids = history.resolve(ctx, BEATMAP, target);
            history.remember(ctx, ids);
            var response = APIHelper.getBeatmapAnalysisResponse(ids.beatmapId(), target.nextArgument(ctx));
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.beatmapMessage(ctx, response)));
        }
    }

    public void handleAp(Context ctx) {
        var target = history.parseArguments(ctx, CommandUsage.AP, Integer.MAX_VALUE);
        if (target == null) return;
        try (var timing = taskCoordinator.beginRequest(ctx, "Audio Preview")) {
            var ids = history.resolve(ctx, BEATMAPSET, target);
            history.remember(ctx, ids);
            long id = ids.beatmapsetId();
            ctx.sendReply(PendingMessage.ofVoiceUrl("https://b.ppy.sh/preview/" + id + ".mp3").doUpload(false));
        }
    }

    public void handleBpv(Context ctx) {
        var target = history.parseArguments(ctx, CommandUsage.BPV, 1, arg -> arg.startsWith("+"));
        if (target == null) return;
        try (var timing = taskCoordinator.beginRequest(ctx, "Beatmap Preview Render")) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "正在获取谱面以及回放文件，请稍作等待喵..."));
            var qqUpload = taskCoordinator.createVideoUploadRequest(ctx);
            var ids = history.resolve(ctx, BEATMAP, target);
            history.remember(ctx, ids);
            var task = APIHelper.createBeatmapPreviewTask(ids.beatmapId(), target.nextArgument(ctx), qqUpload);
            videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
            ctx.sendReply(replyFactory.replayMessage(ctx, task));
            var result = taskCoordinator.waitForReplay(task);
            if (result == null) {
                ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "回放视频生成失败，请稍后重试。"));
                return;
            }
            if (ctx.sendReply(taskCoordinator.replayVideoMessage(result)).success()) {
                taskCoordinator.removeReplayResult(task.taskId());
            }
        }
    }

    public void handleBgp(Context ctx) {
        var target = history.parseArguments(ctx, CommandUsage.BGP, Integer.MAX_VALUE);
        if (target == null) return;
        try (var timing = taskCoordinator.beginRequest(ctx, "Background Preview")) {
            var ids = history.resolve(ctx, BEATMAP, target);
            history.remember(ctx, ids);
            var response = APIHelper.getBeatmapBgResponse(ids.beatmapId());
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.bgpMessage(ctx, response)));
        }
    }

    public void handleDl(Context ctx) {
        var target = history.parseArguments(ctx, CommandUsage.DL, 0);
        if (target == null) return;
        try (var timing = taskCoordinator.beginRequest(ctx, "Download Beatmap")) {
            var ids = history.resolve(ctx, BEATMAPSET, target);
            history.remember(ctx, ids);
            var response = APIHelper.getLookupBeatmapsetResponse(ids.beatmapsetId(), accessTokenProvider.apply(ctx.senderUserId()));
            ctx.sendReply(replyFactory.dlMessage(ctx, response));
        }
    }

    public void handleMs(Context ctx) {
        var target = history.parseArguments(ctx, "用法：/ms <谱面集ID 或 快捷查询>", 0);
        if (target == null) return;
        try (var timing = taskCoordinator.beginRequest(ctx, "Beatmapset")) {
            var ids = history.resolve(ctx, BEATMAPSET, target);
            history.remember(ctx, ids);
            var response = APIHelper.getBeatmapsetResponse(ids.beatmapsetId());
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

package xyz.zcraft.seira.command.handler;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.api.data.SearchQuery;
import xyz.zcraft.seira.api.data.SearchResultItem;
import xyz.zcraft.seira.api.data.VideoRenderRecord;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.target.CommandTargets;
import static xyz.zcraft.seira.command.target.TargetKind.*;
import xyz.zcraft.seira.command.TaskCoordinator;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.reply.CommandUsage;
import xyz.zcraft.seira.command.reply.ReplyFactory;

import java.util.List;
import java.util.function.Function;

import static xyz.zcraft.seira.command.reply.ReplyFactory.at;

public final class BeatmapCommandHandler {
    private final Resolver resolver;
    private final CommandTargets targets;
    private final TaskCoordinator taskCoordinator;
    private final ReplyFactory replyFactory;
    private final VideoRenderRecord videoRenderRecord;
    private final Function<String, String> accessTokenProvider;

    public BeatmapCommandHandler(
            Resolver resolver,
            CommandTargets targets,
            TaskCoordinator taskCoordinator,
            ReplyFactory replyFactory,
            VideoRenderRecord videoRenderRecord,
            Function<String, String> accessTokenProvider
    ) {
        this.resolver = resolver;
        this.targets = targets;
        this.taskCoordinator = taskCoordinator;
        this.replyFactory = replyFactory;
        this.videoRenderRecord = videoRenderRecord;
        this.accessTokenProvider = accessTokenProvider;
    }

    public void handleDaily(Context ctx) {
        taskCoordinator.runApiRequest(ctx, "Daily Challenge", () ->
                ctx.sendReply(PendingMessage.ofMarkdownRaw(APIHelper.getDaily()))
        );
    }

    public void handleM(Context ctx) {
        var target = targets.parse(ctx, BEATMAP, CommandUsage.M, 1);
        if (target == null) return;
        taskCoordinator.runImageRequest(ctx, "Beatmap",
                () -> APIHelper.getBeatmapResponse(targets.resolve(ctx, target),
                        target.nextArgument(ctx), accessTokenProvider.apply(ctx.senderUserId())),
                replyFactory::beatmapMessage);
    }

    public void handleBma(Context ctx) {
        var target = targets.parse(ctx, BEATMAP, CommandUsage.BMA, 1);
        if (target == null) return;
        taskCoordinator.runImageRequest(ctx, "Beatmap Analysis",
                () -> APIHelper.getBeatmapAnalysisResponse(targets.resolve(ctx, target),
                        target.nextArgument(ctx), accessTokenProvider.apply(ctx.senderUserId())),
                replyFactory::beatmapMessage);
    }

    public void handleAp(Context ctx) {
        var target = targets.parse(ctx, BEATMAPSET, CommandUsage.AP, Integer.MAX_VALUE);
        if (target == null) return;
        taskCoordinator.runApiRequest(ctx, "Audio Preview", () -> {
            long id = APIHelper.lookupBeatmapset(targets.resolve(ctx, target), accessTokenProvider.apply(ctx.senderUserId()));
            ctx.sendReply(PendingMessage.ofVoiceUrl("https://b.ppy.sh/preview/" + id + ".mp3").doUpload(false));
        });
    }

    public void handleBpv(Context ctx) {
        var target = targets.parse(ctx, BEATMAP, CommandUsage.BPV, 1, arg -> arg.startsWith("+"));
        if (target == null) return;
        taskCoordinator.runReplayRequest(ctx, "Beatmap Preview Render", qqUpload -> {
            var task = APIHelper.createBeatmapPreviewTask(targets.resolve(ctx, target),
                    target.nextArgument(ctx), accessTokenProvider.apply(ctx.senderUserId()), qqUpload);
            videoRenderRecord.updateRenderTask(ctx.senderUserId(), task.taskId());
            return task;
        }, replyFactory::replayMessage);
    }

    public void handleBgp(Context ctx) {
        var target = targets.parse(ctx, BEATMAP, CommandUsage.BGP, Integer.MAX_VALUE);
        if (target == null) return;
        taskCoordinator.runImageRequest(ctx, "Background Preview",
                () -> APIHelper.getBeatmapBgResponse(targets.resolve(ctx, target), accessTokenProvider.apply(ctx.senderUserId())),
                replyFactory::bgpMessage);
    }

    public void handleDl(Context ctx) {
        var target = targets.parse(ctx, BEATMAPSET, CommandUsage.DL, 0);
        if (target == null) return;
        taskCoordinator.runApiRequest(ctx, "Download Beatmap", () ->
                ctx.sendReply(replyFactory.dlMessage(ctx,
                        APIHelper.getLookupBeatmapsetResponse(targets.resolve(ctx, target), accessTokenProvider.apply(ctx.senderUserId())))));
    }

    public void handleMs(Context ctx) {
        var target = targets.parse(ctx, BEATMAPSET, "用法：/ms <谱面集ID 或 快捷查询>", 0);
        if (target == null) return;
        taskCoordinator.runImageRequest(ctx, "Beatmapset",
                () -> APIHelper.getBeatmapsetResponse(targets.resolve(ctx, target), accessTokenProvider.apply(ctx.senderUserId())),
                replyFactory::beatmapsetMessage);
    }

    public void handleSms(Context ctx) {
        final SearchQuery searchQuery = resolver.resolveSearchQuery(ctx.query());
        if (searchQuery == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "用法：/sms [#页数] <搜索关键字>"));
            return;
        }
        taskCoordinator.runApiRequest(ctx, "Search Beatmapset", () -> {
            Response<List<SearchResultItem>> searchResponse = APIHelper.searchBeatmapSetResponse(searchQuery);
            ctx.sendReply(replyFactory.searchMessage(ctx, searchResponse, searchQuery));
        });
    }

}

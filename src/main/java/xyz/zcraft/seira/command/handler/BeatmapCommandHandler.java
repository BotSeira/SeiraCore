package xyz.zcraft.seira.command.handler;

import org.jline.utils.Log;
import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.api.data.Response;
import xyz.zcraft.seira.api.data.SearchQuery;
import xyz.zcraft.seira.api.data.SearchResultItem;
import xyz.zcraft.seira.api.data.VideoRenderRecord;
import xyz.zcraft.seira.bot.data.PendingMessage;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.parse.TargetInput;
import xyz.zcraft.seira.command.TargetHistory;
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
        var target = ctx.argumentCount() == 0
                ? TargetInput.memory() : TargetInput.read(ctx.args());
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - target.consumedArgs() > 1) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.M));
            return;
        }
        try (var _ = taskCoordinator.beginRequest(ctx, "Beatmap")) {
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
            var response = APIHelper.getBeatmapResponse(beatmapId, (ctx.argumentCount() > target.consumedArgs() ? ctx.argument(target.consumedArgs()) : null));
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.beatmapMessage(ctx, response)));
        }
    }

    public void handleBma(Context ctx) {
        var target = ctx.argumentCount() == 0
                ? TargetInput.memory() : TargetInput.read(ctx.args());
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - target.consumedArgs() > 1) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.BMA));
            return;
        }
        try (var timing = taskCoordinator.beginRequest(ctx, "Beatmap Analysis")) {
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
            var response = APIHelper.getBeatmapAnalysisResponse(beatmapId, (ctx.argumentCount() > target.consumedArgs() ? ctx.argument(target.consumedArgs()) : null));
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.beatmapMessage(ctx, response)));
        }
    }

    public void handleAp(Context ctx) {
        var target = ctx.argumentCount() == 0
                ? TargetInput.memory() : TargetInput.read(ctx.args());
        var remembered = history.get(ctx);
        if (target.kind() == TargetInput.Kind.MEMORY && remembered == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.AP));
            return;
        }
        try (var timing = taskCoordinator.beginRequest(ctx, "Audio Preview")) {
            var previous = target.kind() == TargetInput.Kind.MEMORY ? remembered : null;
            Long beatmapId = previous == null ? null : previous.beatmapId();
            Long beatmapsetId = previous == null ? null : previous.beatmapsetId();
            String scoreId = previous == null ? null : previous.scoreId();
            switch (target.kind()) {
                case ID -> beatmapsetId = Long.parseLong(target.id());
                case MAP -> beatmapId = Long.parseLong(target.id());
                case SCORE -> scoreId = target.id();
                case SET -> beatmapsetId = Long.parseLong(target.id());
                case RS, RP, BP -> {
                    String player = resolver.player(target.player(), ctx.senderUserId());
                    long uid = APIHelper.resolveUid(player);
                    scoreId = APIHelper.lookupPlayerScore(uid, target.scoreList(), target.index(), List.of(), null);
                }
                case MP -> beatmapsetId = APIHelper.lookupMultiplayerBeatmapset(accessTokenProvider.apply(ctx.senderUserId()));
                case MEMORY -> {}
            }
            if (beatmapsetId == null) {
                if (beatmapId == null && scoreId != null) beatmapId = APIHelper.getScoreBeatmapId(scoreId);
                if (beatmapId == null) throw new ResolutionException("请指定指令目标喵");
                beatmapsetId = APIHelper.lookupBeatmapsetForBeatmap(beatmapId, accessTokenProvider.apply(ctx.senderUserId()));
            }
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
            ctx.sendReply(PendingMessage.ofVoiceUrl("https://b.ppy.sh/preview/" + beatmapsetId + ".mp3").doUpload(false));
        }
    }

    public void handleBpv(Context ctx) {
        var target = ctx.argumentCount() == 0 || ctx.argument(0).startsWith("+") || TimeDurationParser.isTimeRange(ctx.argument(0))
                ? TargetInput.memory() : TargetInput.read(ctx.args());
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - target.consumedArgs() > 2) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.BPV));
            return;
        }

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
            var task = APIHelper.createBeatmapPreviewTask(beatmapId, mods, range, qqUpload);
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
                taskCoordinator.removeReplayResult(task.taskId());
            }
        }
    }

    public void handleBgp(Context ctx) {
        var target = ctx.argumentCount() == 0
                ? TargetInput.memory() : TargetInput.read(ctx.args());
        var remembered = history.get(ctx);
        if (target.kind() == TargetInput.Kind.MEMORY && remembered == null) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.BGP));
            return;
        }
        try (var timing = taskCoordinator.beginRequest(ctx, "Background Preview")) {
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
            var response = APIHelper.getBeatmapBgResponse(beatmapId);
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
            ctx.sendReply(taskCoordinator.imageMessage(response, replyFactory.bgpMessage(ctx, response)));
        }
    }

    public void handleDl(Context ctx) {
        var target = ctx.argumentCount() == 0
                ? TargetInput.memory() : TargetInput.read(ctx.args());
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - target.consumedArgs() > 0) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + CommandUsage.DL));
            return;
        }
        try (var timing = taskCoordinator.beginRequest(ctx, "Download Beatmap")) {
            var previous = target.kind() == TargetInput.Kind.MEMORY ? remembered : null;
            Long beatmapId = previous == null ? null : previous.beatmapId();
            Long beatmapsetId = previous == null ? null : previous.beatmapsetId();
            String scoreId = previous == null ? null : previous.scoreId();
            switch (target.kind()) {
                case ID -> beatmapsetId = Long.parseLong(target.id());
                case MAP -> beatmapId = Long.parseLong(target.id());
                case SCORE -> scoreId = target.id();
                case SET -> beatmapsetId = Long.parseLong(target.id());
                case RS, RP, BP -> {
                    String player = resolver.player(target.player(), ctx.senderUserId());
                    long uid = APIHelper.resolveUid(player);
                    scoreId = APIHelper.lookupPlayerScore(uid, target.scoreList(), target.index(), List.of(), null);
                }
                case MP -> beatmapsetId = APIHelper.lookupMultiplayerBeatmapset(accessTokenProvider.apply(ctx.senderUserId()));
                case MEMORY -> {}
            }
            if (beatmapsetId == null) {
                if (beatmapId == null && scoreId != null) beatmapId = APIHelper.getScoreBeatmapId(scoreId);
                if (beatmapId == null) throw new ResolutionException("请指定指令目标喵");
                beatmapsetId = APIHelper.lookupBeatmapsetForBeatmap(beatmapId, accessTokenProvider.apply(ctx.senderUserId()));
            }
            var response = APIHelper.getLookupBeatmapsetResponse(beatmapsetId, accessTokenProvider.apply(ctx.senderUserId()));
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
            ctx.sendReply(replyFactory.dlMessage(ctx, response));
        }
    }

    public void handleMs(Context ctx) {
        var target = ctx.argumentCount() == 0
                ? TargetInput.memory() : TargetInput.read(ctx.args());
        var remembered = history.get(ctx);
        if ((target.kind() == TargetInput.Kind.MEMORY && remembered == null)
                || ctx.argumentCount() - target.consumedArgs() > 0) {
            ctx.sendReply(PendingMessage.ofMarkdownRaw(at(ctx) + "用法：/ms <谱面集ID 或 快捷查询>"));
            return;
        }
        try (var timing = taskCoordinator.beginRequest(ctx, "Beatmapset")) {
            var previous = target.kind() == TargetInput.Kind.MEMORY ? remembered : null;
            Long beatmapId = previous == null ? null : previous.beatmapId();
            Long beatmapsetId = previous == null ? null : previous.beatmapsetId();
            String scoreId = previous == null ? null : previous.scoreId();
            switch (target.kind()) {
                case ID -> beatmapsetId = Long.parseLong(target.id());
                case MAP -> beatmapId = Long.parseLong(target.id());
                case SCORE -> scoreId = target.id();
                case SET -> beatmapsetId = Long.parseLong(target.id());
                case RS, RP, BP -> {
                    String player = resolver.player(target.player(), ctx.senderUserId());
                    long uid = APIHelper.resolveUid(player);
                    scoreId = APIHelper.lookupPlayerScore(uid, target.scoreList(), target.index(), List.of(), null);
                }
                case MP -> beatmapsetId = APIHelper.lookupMultiplayerBeatmapset(accessTokenProvider.apply(ctx.senderUserId()));
                case MEMORY -> {}
            }
            if (beatmapsetId == null) {
                if (beatmapId == null && scoreId != null) beatmapId = APIHelper.getScoreBeatmapId(scoreId);
                if (beatmapId == null) throw new ResolutionException("请指定指令目标喵");
                beatmapsetId = APIHelper.lookupBeatmapsetForBeatmap(beatmapId, accessTokenProvider.apply(ctx.senderUserId()));
            }
            var response = APIHelper.getBeatmapsetResponse(beatmapsetId);
            history.remember(ctx, beatmapsetId, beatmapId, scoreId);
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

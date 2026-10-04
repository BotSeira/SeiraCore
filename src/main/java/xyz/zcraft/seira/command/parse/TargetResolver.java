package xyz.zcraft.seira.command.parse;

import xyz.zcraft.seira.api.OstellaApi;
import xyz.zcraft.seira.command.Context;
import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.TargetHistory;

import java.util.List;
import java.util.function.Function;

/**
 * Converts an already parsed target to the IDs needed by a command.
 * Commands retain option parsing, replies and history writes; only MEMORY uses remembered IDs.
 */
public final class TargetResolver {
    private final Resolver resolver;
    private final Function<String, String> accessTokenProvider;

    public TargetResolver(Resolver resolver, Function<String, String> accessTokenProvider) {
        this.resolver = resolver;
        this.accessTokenProvider = accessTokenProvider;
    }

    public TargetHistory.Ids beatmap(Context ctx, TargetInput target, TargetHistory.Ids remembered) {
        var previous = target.kind() == TargetInput.Kind.MEMORY ? remembered : null;
        Long beatmapId = previous == null ? null : previous.beatmapId();
        Long beatmapsetId = previous == null ? null : previous.beatmapsetId();
        String scoreId = previous == null ? null : previous.scoreId();
        switch (target.kind()) {
            case ID, MAP -> beatmapId = Long.parseLong(target.id());
            case SCORE -> scoreId = target.id();
            case SET -> {
                beatmapsetId = Long.parseLong(target.id());
                beatmapId = OstellaApi.lookupBeatmapInSet(beatmapsetId, target.index(), accessTokenProvider.apply(ctx.senderUserId()));
            }
            case RS, RP, BP -> {
                String player = resolver.player(target.player(), ctx.senderUserId());
                long uid = OstellaApi.resolveUid(player);
                scoreId = OstellaApi.lookupPlayerScore(uid, target.scoreList(), target.index(), List.of(), null);
            }
            case MP ->
                    beatmapId = OstellaApi.lookupMultiplayerBeatmap(target.id(),
                            target.id() == null ? accessTokenProvider.apply(ctx.senderUserId()) : null);
            case MEMORY -> {
            }
        }
        if (beatmapId == null && scoreId != null) beatmapId = OstellaApi.getScoreBeatmapId(scoreId);
        if (beatmapId == null) throw new ResolutionException("请指定指令目标谱面喵");
        return new TargetHistory.Ids(beatmapsetId, beatmapId, scoreId);
    }

    public TargetHistory.Ids beatmapset(Context ctx, TargetInput target, TargetHistory.Ids remembered) {
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
                long uid = OstellaApi.resolveUid(player);
                scoreId = OstellaApi.lookupPlayerScore(uid, target.scoreList(), target.index(), List.of(), null);
            }
            case MP ->
                    beatmapsetId = OstellaApi.lookupMultiplayerBeatmapset(target.id(),
                            target.id() == null ? accessTokenProvider.apply(ctx.senderUserId()) : null);
            case MEMORY -> {
            }
        }
        if (beatmapsetId == null) {
            if (beatmapId == null && scoreId != null) beatmapId = OstellaApi.getScoreBeatmapId(scoreId);
            if (beatmapId == null) throw new ResolutionException("请指定指令目标喵");
            beatmapsetId = OstellaApi.lookupBeatmapsetForBeatmap(beatmapId, accessTokenProvider.apply(ctx.senderUserId()));
        }
        return new TargetHistory.Ids(beatmapsetId, beatmapId, scoreId);
    }

    public TargetHistory.Ids score(Context ctx, TargetInput target, TargetHistory.Ids remembered) {
        return score(ctx, target, remembered, null, List.of(), null);
    }

    // Overrides and filters have already been parsed by /s. A direct score is retained unless overridden.
    public TargetHistory.Ids score(Context ctx, TargetInput target, TargetHistory.Ids remembered,
                                   String userOverride, List<String> filters, String mod) {
        var previous = target.kind() == TargetInput.Kind.MEMORY ? remembered : null;
        Long beatmapId = previous == null ? null : previous.beatmapId();
        Long beatmapsetId = previous == null ? null : previous.beatmapsetId();
        String scoreId = previous == null ? null : previous.scoreId();
        boolean selectedPlayerScore = false;
        switch (target.kind()) {
            case ID, SCORE -> scoreId = target.id();
            case MAP -> beatmapId = Long.parseLong(target.id());
            case SET -> {
                beatmapsetId = Long.parseLong(target.id());
                String player = userOverride == null ? resolver.player(null, ctx.senderUserId()) : userOverride;
                long uid = OstellaApi.resolveUid(player);
                scoreId = OstellaApi.lookupBeatmapsetScore(beatmapsetId, target.index(), uid, filters, mod);
                selectedPlayerScore = true;
            }
            case RS, RP, BP -> {
                String player = userOverride == null ? resolver.player(target.player(), ctx.senderUserId()) : userOverride;
                long uid = OstellaApi.resolveUid(player);
                scoreId = OstellaApi.lookupPlayerScore(uid, target.scoreList(), target.index(), filters, mod);
                selectedPlayerScore = true;
            }
            case MP ->
                    beatmapId = OstellaApi.lookupMultiplayerBeatmap(target.id(),
                            target.id() == null ? accessTokenProvider.apply(ctx.senderUserId()) : null);
            case MEMORY -> {
            }
        }
        if ((userOverride != null || mod != null) && scoreId != null && !selectedPlayerScore) {
            if (beatmapId == null) beatmapId = OstellaApi.getScoreBeatmapId(scoreId);
            scoreId = null;
        }
        if (scoreId == null) {
            if (beatmapId == null) throw new ResolutionException("请指定指令目标谱面喵");
            String player = userOverride == null ? resolver.player(target.player(), ctx.senderUserId()) : userOverride;
            long uid = OstellaApi.resolveUid(player);
            scoreId = OstellaApi.lookupBeatmapScore(beatmapId, uid, filters, mod);
        }
        return new TargetHistory.Ids(beatmapsetId, beatmapId, scoreId);
    }

    // /lb needs only the map ID: preserve its direct shortcut lookup without fetching the score.
    public long beatmapId(Context ctx, TargetInput target) {
        if (target.kind() == TargetInput.Kind.RS || target.kind() == TargetInput.Kind.RP
                || target.kind() == TargetInput.Kind.BP) {
            long uid = OstellaApi.resolveUid(resolver.player(target.player(), ctx.senderUserId()));
            return OstellaApi.lookupPlayerScoreBeatmap(uid, target.scoreList(), target.index(),
                    accessTokenProvider.apply(ctx.senderUserId()));
        }
        return beatmap(ctx, target, null).beatmapId();
    }
}

package xyz.zcraft.seira.command;

import xyz.zcraft.seira.api.APIHelper;
import xyz.zcraft.seira.command.parse.Resolver;
import xyz.zcraft.seira.command.parse.ShortcutTarget;
import xyz.zcraft.seira.data.UserRef;

import java.util.List;
import java.util.function.Function;

public final class TargetLookup {
    private final Resolver resolver;
    private final Function<String, String> accessTokenProvider;

    public TargetLookup(Resolver resolver, Function<String, String> accessTokenProvider) {
        this.resolver = resolver;
        this.accessTokenProvider = accessTokenProvider;
    }

    public TargetHistory.Ids beatmap(Context ctx, ShortcutTarget input, TargetHistory.Ids previous) {
        return lookup(ctx, input, previous, Type.BEATMAP, null, List.of(), null);
    }

    public TargetHistory.Ids beatmapset(Context ctx, ShortcutTarget input, TargetHistory.Ids previous) {
        return lookup(ctx, input, previous, Type.BEATMAPSET, null, List.of(), null);
    }

    public TargetHistory.Ids score(Context ctx, ShortcutTarget input, TargetHistory.Ids previous) {
        return score(ctx, input, previous, null, List.of(), null);
    }

    public TargetHistory.Ids score(Context ctx, ShortcutTarget input, TargetHistory.Ids previous,
                                   UserRef userOverride, List<String> filters, String mod) {
        return lookup(ctx, input, previous, Type.SCORE, userOverride, filters, mod);
    }

    public TargetHistory.Ids scoreOnBeatmap(Context ctx, ShortcutTarget input, TargetHistory.Ids previous,
                                            UserRef player, List<String> filters, String mod) {
        UserRef targetPlayer = requirePlayer(ctx, player);
        var map = beatmap(ctx, input, previous);
        String scoreId = APIHelper.lookupBeatmapScore(map.beatmapId(), APIHelper.resolveUid(targetPlayer), filters, mod);
        return new TargetHistory.Ids(map.beatmapsetId(), map.beatmapId(), scoreId);
    }


    private TargetHistory.Ids lookup(Context ctx, ShortcutTarget input, TargetHistory.Ids previous,
                                     Type type, UserRef userOverride, List<String> filters, String mod) {
        if (input != null) previous = null;
        Long beatmapId = previous == null ? null : previous.beatmapId();
        Long beatmapsetId = previous == null ? null : previous.beatmapsetId();
        String scoreId = previous == null ? null : previous.scoreId();
        UserRef player = userOverride != null ? userOverride : input == null ? null : input.userRef();
        boolean selectedPlayerScore = false;
        if (input != null) {
            if (input.isError()) throw new ResolutionException(input.errorMessage());
            if (input.isLocalScore()) {
                scoreId = input.localScoreId();
            } else if (!input.isMacro()) {
                switch (type) {
                    case BEATMAP -> beatmapId = input.explicitId();
                    case BEATMAPSET -> beatmapsetId = input.explicitId();
                    case SCORE -> scoreId = input.explicitId().toString();
                }
            } else {
                switch (input.macroType()) {
                    case "m" -> beatmapId = input.explicitId();
                    case "s" -> scoreId = input.explicitId().toString();
                    case "ms" -> {
                        beatmapsetId = input.explicitId();
                        if (type != Type.BEATMAPSET) {
                            if (input.macroIndex() == null) throw new ResolutionException("请指定指令目标谱面喵");
                            if (type == Type.BEATMAP) {
                                beatmapId = APIHelper.lookupBeatmapInSet(beatmapsetId, input.macroIndex(), accessTokenProvider.apply(ctx.senderUserId()));
                            } else {
                                player = requirePlayer(ctx, player);
                                scoreId = APIHelper.lookupBeatmapsetScore(beatmapsetId, input.macroIndex(), APIHelper.resolveUid(player), filters, mod);
                                selectedPlayerScore = true;
                            }
                        }
                    }
                    case "rs", "rp", "bp" -> {
                        player = requirePlayer(ctx, player);
                        scoreId = APIHelper.lookupPlayerScore(APIHelper.resolveUid(player), input.macroType(), input.macroIndex(), filters, mod);
                        selectedPlayerScore = true;
                    }
                    case "mp" -> {
                        String token = accessTokenProvider.apply(ctx.senderUserId());
                        if (type == Type.BEATMAPSET) beatmapsetId = APIHelper.lookupMultiplayerBeatmapset(token);
                        else beatmapId = APIHelper.lookupMultiplayerBeatmap(token);
                    }
                    default -> throw new ResolutionException("未知的快捷查询");
                }
            }
        }

        if (type == Type.SCORE && (userOverride != null || mod != null) && scoreId != null && !selectedPlayerScore) {
            if (beatmapId == null) beatmapId = APIHelper.getScoreBeatmapId(scoreId);
            scoreId = null;
        }
        switch (type) {
            case BEATMAP -> {
                if (beatmapId == null) {
                    if (scoreId == null) throw new ResolutionException("请指定指令目标谱面喵");
                    beatmapId = APIHelper.getScoreBeatmapId(scoreId);
                }
            }
            case BEATMAPSET -> {
                if (beatmapsetId == null) {
                    if (beatmapId == null && scoreId != null) beatmapId = APIHelper.getScoreBeatmapId(scoreId);
                    if (beatmapId == null) throw new ResolutionException("请指定指令目标喵");
                    beatmapsetId = APIHelper.lookupBeatmapsetForBeatmap(beatmapId, accessTokenProvider.apply(ctx.senderUserId()));
                }
            }
            case SCORE -> {
                if (scoreId == null) {
                    if (beatmapId == null) throw new ResolutionException("请指定指令目标谱面喵");
                    player = requirePlayer(ctx, player);
                    scoreId = APIHelper.lookupBeatmapScore(beatmapId, APIHelper.resolveUid(player), filters, mod);
                }
            }
        }
        return new TargetHistory.Ids(beatmapsetId, beatmapId, scoreId);
    }

    private UserRef requirePlayer(Context ctx, UserRef player) {
        if (player != null) return player;
        Long uid = resolver.resolveBoundUid(ctx.senderUserId());
        if (uid == null) throw new ResolutionException("请先绑定 osu! 账号，再查找记忆谱面上的成绩喵");
        return new UserRef.ByUid(uid);
    }

    private enum Type {BEATMAP, BEATMAPSET, SCORE}
}

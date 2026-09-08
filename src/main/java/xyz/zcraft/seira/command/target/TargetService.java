package xyz.zcraft.seira.command.target;

import xyz.zcraft.seira.command.ResolutionException;
import xyz.zcraft.seira.command.TargetHistory;
import xyz.zcraft.seira.data.UserRef;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Resolves typed requests and commits their associations atomically, without knowing command names. */
public final class TargetService {
    private final TargetHistory history;
    private final TargetLookup lookup;
    private final Function<String, Long> boundUid;
    private final Function<String, String> auth;

    public TargetService(TargetHistory history, TargetLookup lookup,
                         Function<String, Long> boundUid, Function<String, String> auth) {
        this.history = Objects.requireNonNull(history);
        this.lookup = Objects.requireNonNull(lookup);
        this.boundUid = Objects.requireNonNull(boundUid);
        this.auth = Objects.requireNonNull(auth);
    }

    public TargetRequest recall(String caller, TargetKind kind) {
        TargetId target = history.get(caller).select(kind);
        return target == null ? null : new TargetRequest(kind, new TargetQuery.Reference(target, null), true);
    }

    public TargetRequest recallShowcase(String caller) {
        TargetId score = history.get(caller).score();
        return score != null && score.isLocalScore()
                ? new TargetRequest(TargetKind.BEATMAP, new TargetQuery.Reference(score, null), true)
                : recall(caller, TargetKind.BEATMAP);
    }

    public TargetId resolve(String caller, TargetRequest request) {
        return resolve(caller, request, List.of());
    }

    public TargetId resolve(String caller, TargetRequest request, List<String> filters) {
        if (request == null) throw new ResolutionException("没有可用的目标记忆，请先指定目标。");
        List<String> scoreFilters = List.copyOf(filters);
        TargetMemory result = history.update(caller, previous -> {
            TargetMemory memory = request.remembered()
                    && request.query() instanceof TargetQuery.Reference ref && previous.contains(ref.target())
                    ? previous : TargetMemory.EMPTY;
            return switch (request.query()) {
                case TargetQuery.Reference ref -> convert(caller, ref, request.kind(), memory, scoreFilters);
                case TargetQuery.ScoreList list -> {
                    // Freeze the list position before any cross-type lookup.
                    TargetId score = TargetId.score(lookup.score(list, scoreFilters));
                    yield convert(caller, new TargetQuery.Reference(score, null), request.kind(), memory, scoreFilters);
                }
                case TargetQuery.Difficulty difficulty -> {
                    TargetMemory withSet = memory.with(TargetId.beatmapset(difficulty.setId()));
                    yield switch (request.kind()) {
                        case BEATMAPSET -> withSet;
                        case BEATMAP -> withSet.with(TargetId.beatmap(lookup.beatmap(difficulty, auth.apply(caller))));
                        case SCORE -> withSet.with(TargetId.score(lookup.score(withPlayer(caller, difficulty), scoreFilters)));
                    };
                }
                case TargetQuery.Room room -> {
                    if (request.kind() == TargetKind.BEATMAPSET) {
                        yield memory.with(TargetId.beatmapset(lookup.beatmapset(room, auth.apply(caller))));
                    }
                    TargetId map = TargetId.beatmap(lookup.beatmap(room, auth.apply(caller)));
                    yield convert(caller, new TargetQuery.Reference(map, room.user()), request.kind(), memory, scoreFilters);
                }
            };
        });
        return result.select(request.kind());
    }

    private TargetMemory convert(String caller, TargetQuery.Reference source, TargetKind desired,
                                 TargetMemory memory, List<String> filters) {
        TargetId target = source.target();
        memory = memory.with(target);
        boolean scoreOverride = desired == TargetKind.SCORE && source.user() != null;
        if (target.kind() == desired && !scoreOverride) return memory;

        if (target.kind() == TargetKind.BEATMAPSET) {
            throw new ResolutionException("只记忆了谱面集，请使用 谱面集ID#难度序号 或指定谱面ID。");
        }
        // An override on a score selects that player's score on the same map.
        if (scoreOverride && target.kind() == TargetKind.SCORE) {
            TargetId map = memory.beatmap() != null ? memory.beatmap()
                    : TargetId.beatmap(lookup.beatmap(source, auth.apply(caller)));
            source = new TargetQuery.Reference(map, source.user());
            memory = memory.with(map);
        }
        TargetId resolved = switch (desired) {
            case BEATMAP -> TargetId.beatmap(lookup.beatmap(source, auth.apply(caller)));
            case BEATMAPSET -> TargetId.beatmapset(lookup.beatmapset(source, auth.apply(caller)));
            case SCORE -> TargetId.score(lookup.score(withPlayer(caller, source), filters));
        };
        return memory.with(resolved);
    }

    private TargetQuery withPlayer(String caller, TargetQuery query) {
        if (query.user() != null) return query;
        Long uid = boundUid.apply(caller);
        if (uid == null) throw new ResolutionException("请先绑定 osu! 账号，再查找记忆谱面上的成绩。");
        return query.withUser(new UserRef.ByUid(uid));
    }
}

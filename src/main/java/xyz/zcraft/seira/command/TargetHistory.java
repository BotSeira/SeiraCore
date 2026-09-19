package xyz.zcraft.seira.command;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Per-sender target memory. No parsing, binding lookup, API calls or replies. */
public final class TargetHistory {
    private final ConcurrentMap<String, Ids> users = new ConcurrentHashMap<>();

    public Ids get(Context ctx) {
        return users.get(ctx.senderUserId());
    }

    public void remember(Context ctx, Long beatmapsetId, Long beatmapId, String scoreId) {
        remember(ctx, new Ids(beatmapsetId, beatmapId, scoreId));
    }

    public void remember(Context ctx, Ids ids) {
        users.put(ctx.senderUserId(), ids);
    }

    /** Immutable snapshot: IDs belong to the same target, and missing associations remain null. */
    public record Ids(Long beatmapsetId, Long beatmapId, String scoreId) {}
}

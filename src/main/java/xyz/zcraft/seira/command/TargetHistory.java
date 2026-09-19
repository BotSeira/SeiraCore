package xyz.zcraft.seira.command;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    public record Ids(Long beatmapsetId, Long beatmapId, String scoreId) {}
}

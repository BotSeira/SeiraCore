package xyz.zcraft.seira.watch;

import java.util.Objects;

public record WatchTarget(long userId, String username, String qqOpenId) {
    public WatchTarget {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        username = Objects.requireNonNull(username, "username");
        qqOpenId = Objects.requireNonNull(qqOpenId, "qqOpenId");
    }
}

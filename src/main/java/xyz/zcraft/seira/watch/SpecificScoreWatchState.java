package xyz.zcraft.seira.watch;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Persisted state for one group's /wx watch.
 */
public record SpecificScoreWatchState(
        String groupId,
        Set<Long> userIds,
        Set<Long> beatmapIds,
        Map<Long, Long> lastScoreIds,
        Instant startedAt
) {
    public static final Duration LIFETIME = Duration.ofDays(3);

    public SpecificScoreWatchState {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        userIds = Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(userIds)));
        beatmapIds = Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(beatmapIds)));
        lastScoreIds = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(lastScoreIds)));
        Objects.requireNonNull(startedAt, "startedAt");
        if (userIds.isEmpty()) {
            throw new IllegalArgumentException("userIds must not be empty");
        }
        if (beatmapIds.isEmpty()) {
            throw new IllegalArgumentException("beatmapIds must not be empty");
        }
        if (!userIds.containsAll(lastScoreIds.keySet())) {
            throw new IllegalArgumentException("lastScoreIds contains an unwatched user");
        }
    }

    public Instant expiresAt() {
        return startedAt.plus(LIFETIME);
    }
}

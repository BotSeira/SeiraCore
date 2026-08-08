package xyz.zcraft.seira.watch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScoreWatchService implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(ScoreWatchService.class);
    private static final int BATCH_SCORE_LIMIT = 20;

    private final Object lock = new Object();
    private final Map<String, Map<Long, WatchEntry>> watchesByGroup = new LinkedHashMap<>();
    private final WatchApi api;
    private final WatchScoreNotifier notifier;
    private final Duration pollInterval;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ScoreWatchService(WatchApi api, WatchScoreNotifier notifier, Duration pollInterval) {
        this(api, notifier, pollInterval, Clock.systemUTC());
    }

    ScoreWatchService(WatchApi api, WatchScoreNotifier notifier, Duration pollInterval, Clock clock) {
        this.api = Objects.requireNonNull(api);
        this.notifier = Objects.requireNonNull(notifier);
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        this.clock = Objects.requireNonNull(clock);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "seira-score-watch");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Score watch service has been closed");
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleWithFixedDelay(
                this::pollSafely,
                pollInterval.toMillis(),
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public WatchView add(String groupId, WatchTarget target, Duration duration) {
        Objects.requireNonNull(groupId);
        Objects.requireNonNull(target);
        requirePositive(duration, "duration");

        Map<Long, List<RecentScore>> current = api.getRecentScores(List.of(target.userId()), 1);
        Long baselineScoreId = current.getOrDefault(target.userId(), List.of()).stream()
                .findFirst()
                .map(RecentScore::scoreId)
                .orElse(null);
        Instant expiresAt = clock.instant().plus(duration);
        WatchEntry entry = new WatchEntry(target, expiresAt, baselineScoreId);

        synchronized (lock) {
            removeExpiredLocked(clock.instant());
            watchesByGroup.computeIfAbsent(groupId, _ -> new LinkedHashMap<>())
                    .put(target.userId(), entry);
        }
        return view(entry, clock.instant());
    }

    public WatchView remove(String groupId, long userId) {
        synchronized (lock) {
            removeExpiredLocked(clock.instant());
            Map<Long, WatchEntry> groupWatches = watchesByGroup.get(groupId);
            if (groupWatches == null) {
                return null;
            }
            WatchEntry removed = groupWatches.remove(userId);
            if (groupWatches.isEmpty()) {
                watchesByGroup.remove(groupId);
            }
            return removed == null ? null : view(removed, clock.instant());
        }
    }

    public WatchView removeByQqOpenId(String groupId, String qqOpenId) {
        synchronized (lock) {
            removeExpiredLocked(clock.instant());
            Map<Long, WatchEntry> groupWatches = watchesByGroup.get(groupId);
            if (groupWatches == null) {
                return null;
            }
            WatchEntry found = groupWatches.values().stream()
                    .filter(entry -> entry.target.qqOpenId().equals(qqOpenId))
                    .findFirst()
                    .orElse(null);
            if (found == null) {
                return null;
            }
            groupWatches.remove(found.target.userId());
            if (groupWatches.isEmpty()) {
                watchesByGroup.remove(groupId);
            }
            return view(found, clock.instant());
        }
    }

    public int removeAll(String groupId) {
        synchronized (lock) {
            removeExpiredLocked(clock.instant());
            Map<Long, WatchEntry> removed = watchesByGroup.remove(groupId);
            return removed == null ? 0 : removed.size();
        }
    }

    public List<WatchView> list(String groupId) {
        Instant now = clock.instant();
        synchronized (lock) {
            removeExpiredLocked(now);
            Map<Long, WatchEntry> entries = watchesByGroup.get(groupId);
            if (entries == null) {
                return List.of();
            }
            return entries.values().stream()
                    .map(entry -> view(entry, now))
                    .sorted(Comparator.comparing(view -> view.target().username(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public Map<String, List<WatchView>> listAll() {
        Instant now = clock.instant();
        synchronized (lock) {
            removeExpiredLocked(now);
            Map<String, List<WatchView>> result = new LinkedHashMap<>();
            watchesByGroup.forEach((groupId, entries) -> result.put(
                    groupId,
                    entries.values().stream()
                            .map(entry -> view(entry, now))
                            .sorted(Comparator.comparing(
                                    watch -> watch.target().username(),
                                    String.CASE_INSENSITIVE_ORDER
                            ))
                            .toList()
            ));
            return Map.copyOf(result);
        }
    }

    public Status status() {
        synchronized (lock) {
            removeExpiredLocked(clock.instant());
            int taskCount = watchesByGroup.values().stream().mapToInt(Map::size).sum();
            return new Status(started.get() && !closed.get(), watchesByGroup.size(), taskCount, pollInterval);
        }
    }

    public boolean requestPoll() {
        if (!started.get() || closed.get()) {
            return false;
        }
        try {
            scheduler.execute(this::pollSafely);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    public void pollNow() {
        Instant now = clock.instant();
        Map<Long, List<WatchRef>> watchesByUid = snapshotByUid(now);
        if (watchesByUid.isEmpty()) {
            return;
        }

        Map<Long, List<RecentScore>> recentScores = api.getRecentScores(watchesByUid.keySet(), BATCH_SCORE_LIMIT);
        Map<Long, byte[]> renderedScores = new HashMap<>();
        for (Map.Entry<Long, List<WatchRef>> userWatches : watchesByUid.entrySet()) {
            long userId = userWatches.getKey();
            List<RecentScore> scores = recentScores.getOrDefault(userId, List.of());
            for (WatchRef watch : userWatches.getValue()) {
                sendNewScores(watch, scores, renderedScores);
            }
        }
    }

    private void sendNewScores(WatchRef watch, List<RecentScore> scores, Map<Long, byte[]> renderedScores) {
        List<RecentScore> newScores = scoresAfter(scores, watch.entry.lastScoreId);
        for (RecentScore score : newScores.reversed()) {
            if (!isCurrentAndActive(watch)) {
                return;
            }
            try {
                byte[] image = renderedScores.computeIfAbsent(
                        score.scoreId(),
                        _ -> api.renderScore(watch.entry.target.userId(), score.scoreId())
                );
                if (!notifier.sendScore(watch.groupId, image)) {
                    LOG.warn("Failed to send watched score {} to group {}", score.scoreId(), watch.groupId);
                    return;
                }
                markSent(watch, score.scoreId());
            } catch (RuntimeException e) {
                LOG.error("Failed to process watched score {} for group {}", score.scoreId(), watch.groupId, e);
                return;
            }
        }
    }

    private Map<Long, List<WatchRef>> snapshotByUid(Instant now) {
        synchronized (lock) {
            removeExpiredLocked(now);
            Map<Long, List<WatchRef>> result = new LinkedHashMap<>();
            watchesByGroup.forEach((groupId, groupWatches) -> groupWatches.forEach((uid, entry) ->
                    result.computeIfAbsent(uid, _ -> new ArrayList<>()).add(new WatchRef(groupId, entry))
            ));
            return result;
        }
    }

    private boolean isCurrentAndActive(WatchRef watch) {
        synchronized (lock) {
            WatchEntry current = watchesByGroup
                    .getOrDefault(watch.groupId, Map.of())
                    .get(watch.entry.target.userId());
            return current == watch.entry && current.expiresAt.isAfter(clock.instant());
        }
    }

    private void markSent(WatchRef watch, long scoreId) {
        synchronized (lock) {
            WatchEntry current = watchesByGroup
                    .getOrDefault(watch.groupId, Map.of())
                    .get(watch.entry.target.userId());
            if (current == watch.entry) {
                current.lastScoreId = scoreId;
            }
        }
    }

    private void pollSafely() {
        try {
            pollNow();
        } catch (RuntimeException e) {
            LOG.error("Failed to poll watched scores", e);
        }
    }

    private void removeExpiredLocked(Instant now) {
        Set<String> emptyGroups = new LinkedHashSet<>();
        watchesByGroup.forEach((groupId, groupWatches) -> {
            groupWatches.values().removeIf(entry -> !entry.expiresAt.isAfter(now));
            if (groupWatches.isEmpty()) {
                emptyGroups.add(groupId);
            }
        });
        emptyGroups.forEach(watchesByGroup::remove);
    }

    private static List<RecentScore> scoresAfter(List<RecentScore> scores, Long lastScoreId) {
        if (scores.isEmpty()) {
            return List.of();
        }
        if (lastScoreId == null) {
            return List.copyOf(scores);
        }
        for (int index = 0; index < scores.size(); index++) {
            if (scores.get(index).scoreId() == lastScoreId) {
                return List.copyOf(scores.subList(0, index));
            }
        }
        return List.copyOf(scores);
    }

    private static WatchView view(WatchEntry entry, Instant now) {
        Duration remaining = Duration.between(now, entry.expiresAt);
        return new WatchView(entry.target, remaining.isNegative() ? Duration.ZERO : remaining);
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
        }
    }

    private static final class WatchEntry {
        private final WatchTarget target;
        private final Instant expiresAt;
        private volatile Long lastScoreId;

        private WatchEntry(WatchTarget target, Instant expiresAt, Long lastScoreId) {
            this.target = target;
            this.expiresAt = expiresAt;
            this.lastScoreId = lastScoreId;
        }
    }

    private record WatchRef(String groupId, WatchEntry entry) {
    }

    public record Status(boolean running, int groupCount, int taskCount, Duration pollInterval) {
    }
}

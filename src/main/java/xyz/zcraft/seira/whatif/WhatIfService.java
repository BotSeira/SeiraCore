package xyz.zcraft.seira.whatif;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.OstellaApi;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Shared snapshot: instant replies, single background refresh, atomic disk cache.
 */
public final class WhatIfService {
    private static final Logger LOG = LogManager.getLogger(WhatIfService.class);
    private static final Gson GSON = new Gson();
    private static final Duration REFRESH_INTERVAL = Duration.ofDays(1);
    private static final Duration RETRY_INTERVAL = Duration.ofMinutes(30);
    private final Path cache;
    private final Function<List<Long>, List<RankPpModel.Sample>> fetcher;
    private final Clock clock;
    private final Executor executor;
    private static final int MAX_SAMPLES = 1024;
    private static final Duration IDLE_INTERVAL = Duration.ofMinutes(5);
    private static volatile WhatIfService shared;
    private final Map<Long, RankPpModel.Sample> pending = new LinkedHashMap<>();
    private volatile Instant lastActivity;
    private volatile Instant lastWork;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile State state;
    private volatile Instant lastAttempt;
    public WhatIfService(Snapshot seed, Path cache,
                         Function<List<Long>, List<RankPpModel.Sample>> fetcher, Clock clock, Executor executor) {
        this.cache = cache;
        this.fetcher = fetcher;
        this.clock = clock;
        this.executor = executor;
        this.state = new State(seed);
        this.lastActivity = clock.instant();
        if (Files.isRegularFile(cache)) {
            try {
                Snapshot saved = decode(GSON.fromJson(Files.readString(cache), StoredSnapshot.class));
                if (!saved.updatedAt().isBefore(seed.updatedAt()) && !saved.updatedAt().isAfter(clock.instant())
                        && saved.samples().size() <= MAX_SAMPLES
                        && saved.samples().stream().noneMatch(s -> s.observedAt() > clock.millis())) {
                    State restored = new State(saved);
                    if (healthy(restored, this.state)
                            && restored.model().samples().size() >= saved.samples().size() * 0.95) {
                        this.state = restored;
                    }
                }
            } catch (Exception e) {
                LOG.warn("Ignoring invalid whatif cache: {}", e.getMessage());
            }
        }
    }

    public static synchronized WhatIfService create() {
        if (shared != null) return shared;
        shared = createShared();
        var timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "whatif-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                shared.maintain(true);
            } catch (RuntimeException e) {
                LOG.warn("Whatif maintenance failed", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
        return shared;
    }

    public static void observeAvailable(RankPpModel.Sample sample) {
        WhatIfService service = shared;
        if (service != null) service.observe(sample);
    }

    public static void recordActivity() {
        WhatIfService service = shared;
        if (service != null) service.lastActivity = service.clock.instant();
    }

    private static WhatIfService createShared() {
        Snapshot seed;
        try (var stream = Objects.requireNonNull(WhatIfService.class.getResourceAsStream("/whatif-osu-snapshot.json"));
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            seed = decode(GSON.fromJson(reader, StoredSnapshot.class));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取内置排名快照。", e);
        }
        return new WhatIfService(seed, Path.of("data", "whatif-osu-snapshot.json"), WhatIfService::fetchSamples,
                Clock.systemUTC(), command -> Thread.ofVirtual().name("whatif-refresh").start(command));
    }

    private static Snapshot decode(StoredSnapshot stored) {
        return new Snapshot(stored.mode(), Instant.parse(stored.updatedAt()), stored.source(), stored.samples());
    }

    private static List<RankPpModel.Sample> fetchSamples(List<Long> ids) {
        var samples = new ArrayList<RankPpModel.Sample>();
        for (int start = 0; start < ids.size(); start += 50) {
            if (start > 0) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Whatif refresh interrupted", e);
                }
            }
            var users = OstellaApi.getWhatIfUsers(ids.subList(start, Math.min(start + 50, ids.size())));
            for (var user : users) {
                var rulesets = user.getStatisticsRulesets();
                var statistics = rulesets == null ? null : rulesets.getOsu();
                if (statistics != null && statistics.getGlobalRank() != null && statistics.getPp() != null) {
                    var sample = new RankPpModel.Sample(user.getId(), statistics.getGlobalRank(), statistics.getPp());
                    if (sample.valid()) samples.add(sample);
                }
            }
        }
        return samples;
    }

    private static boolean healthy(State next, State previous) {
        return next.model().samples().size() >= previous.model().samples().size() * 0.8
                && next.model().first().rank() <= Math.max(10, previous.model().first().rank() * 2)
                && next.model().last().rank() >= previous.model().last().rank() * 0.8;
    }

    private static boolean conflicts(RankPpModel.Sample old, RankPpModel.Sample fresh) {
        return old.userId() == fresh.userId() || old.rank() == fresh.rank()
                || (old.rank() < fresh.rank() && old.pp() <= fresh.pp())
                || (old.rank() > fresh.rank() && old.pp() >= fresh.pp());
    }

    public State current() {
        State current = state;
        lastActivity = clock.instant();
        maintain(false);
        return current;
    }

    /**
     * Accept only complete osu!standard rank/total-PP pairs from successful queries.
     */
    public synchronized void observe(RankPpModel.Sample sample) {
        if (sample == null || !sample.valid()) return;
        long now = clock.millis();
        if (sample.observedAt() < 0 || sample.observedAt() > now || (sample.observedAt() > 0
                && now - sample.observedAt() > Duration.ofHours(48).toMillis())) return;
        var timed = new RankPpModel.Sample(sample.userId(), sample.rank(), sample.pp(),
                sample.observedAt() == 0 ? now : sample.observedAt());
        var existing = pending.get(sample.userId());
        if (existing != null && existing.observedAt() >= timed.observedAt()) return;
        if (pending.size() >= MAX_SAMPLES && existing == null) return;
        pending.put(sample.userId(), timed);
    }

    // Coalesce all network and disk work; queries only enqueue work.
    synchronized void maintain(boolean idleOnly) {
        Instant now = clock.instant();
        boolean full = Duration.between(state.snapshot().updatedAt(), now).compareTo(REFRESH_INTERVAL) >= 0
                && (lastAttempt == null || Duration.between(lastAttempt, now).compareTo(RETRY_INTERVAL) >= 0)
                && (!idleOnly || Duration.between(lastActivity, now).compareTo(IDLE_INTERVAL) >= 0);
        if ((!full && pending.isEmpty()) || refreshing.get()
                || (lastWork != null && Duration.between(lastWork, now).compareTo(Duration.ofMinutes(1)) < 0)) return;
        refreshing.set(true);
        lastWork = now;
        if (full) lastAttempt = now;
        try {
            executor.execute(() -> refresh(full));
        } catch (RuntimeException e) {
            refreshing.set(false);
            LOG.warn("Unable to start whatif refresh", e);
        }
    }

    private void refresh(boolean full) {
        try {
            State previous = state;
            State base = previous;
            Instant started = clock.instant();
            if (full) {
                try {
                    List<Long> ids = previous.snapshot().samples().stream()
                            .map(RankPpModel.Sample::userId).distinct().toList();
                    var fetched = fetcher.apply(ids).stream()
                            .filter(s -> s != null && s.valid() && ids.contains(s.userId()))
                            .map(s -> new RankPpModel.Sample(s.userId(), s.rank(), s.pp(), started.toEpochMilli()))
                            .toList();
                    State candidate = new State(new Snapshot("osu", started,
                            "osu!standard user statistics via oStella", fetched));
                    if (!healthy(candidate, previous)
                            || candidate.model().samples().size() < candidate.snapshot().samples().size() * 0.95)
                        throw new IllegalStateException("Refreshed samples lost coverage or contain conflicting statistics");
                    base = candidate;
                } catch (Exception e) {
                    LOG.warn("Whatif full refresh failed; retaining snapshot: {}", e.getMessage());
                }
            }
            Map<Long, RankPpModel.Sample> observations;
            synchronized (this) {
                observations = new LinkedHashMap<>(pending);
            }
            State next = base;
            for (var sample : observations.values().stream()
                    .sorted(Comparator.comparingLong(RankPpModel.Sample::observedAt)).toList()) {
                if (sample.observedAt() < next.snapshot().updatedAt().toEpochMilli()
                        || clock.millis() - sample.observedAt() > Duration.ofHours(48).toMillis()) continue;
                var points = new ArrayList<>(next.snapshot().samples());
                if (points.stream().anyMatch(p -> conflicts(p, sample)
                        && p.observedAt() >= sample.observedAt())) continue;
                points.removeIf(p -> conflicts(p, sample));
                if (points.size() >= MAX_SAMPLES) continue;
                points.add(sample);
                try {
                    State candidate = new State(new Snapshot("osu", next.snapshot().updatedAt(),
                            next.snapshot().source(), points));
                    if (healthy(candidate, base)) next = candidate;
                } catch (IllegalArgumentException ignored) { /* Keep the last valid curve. */ }
            }
            if (next != previous) {
                persist(next.snapshot());
                state = next;
            }
            synchronized (this) {
                observations.forEach((id, sample) -> pending.remove(id, sample));
            }
        } catch (Exception e) {
            LOG.warn("Whatif update failed; retaining snapshot and pending samples: {}", e.getMessage());
        } finally {
            refreshing.set(false);
        }
    }

    private void persist(Snapshot snapshot) throws IOException {
        Path absolute = cache.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), "whatif-", ".json");
        try {
            Files.writeString(temp, GSON.toJson(new StoredSnapshot(snapshot.mode(), snapshot.updatedAt().toString(),
                    snapshot.source(), snapshot.samples())));
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public record Snapshot(String mode, Instant updatedAt, String source, List<RankPpModel.Sample> samples) {
        public Snapshot {
            if (!"osu".equals(mode) || updatedAt == null || source == null || samples == null) {
                throw new IllegalArgumentException("无效的排名数据快照。");
            }
            Map<Long, RankPpModel.Sample> unique = new LinkedHashMap<>();
            for (var sample : samples) {
                if (sample == null || !sample.valid()) continue;
                var timed = sample.observedAt() == 0
                        ? new RankPpModel.Sample(sample.userId(), sample.rank(), sample.pp(), updatedAt.toEpochMilli())
                        : sample;
                unique.merge(timed.userId(), timed, (a, b) -> a.observedAt() >= b.observedAt() ? a : b);
            }
            samples = List.copyOf(unique.values());
        }
    }

    // Gson handles Instant explicitly via a string DTO instead of reflective JDK access.
    private record StoredSnapshot(String mode, String updatedAt, String source, List<RankPpModel.Sample> samples) {
    }

    public record State(Snapshot snapshot, RankPpModel model) {
        public State(Snapshot snapshot) {
            this(snapshot, new RankPpModel(snapshot.samples()));
        }
    }
}

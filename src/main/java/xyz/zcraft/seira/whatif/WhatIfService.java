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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
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
    private final List<Long> sampleIds;
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
        this.sampleIds = seed.samples().stream().map(RankPpModel.Sample::userId).distinct().toList();
        if (Files.isRegularFile(cache)) {
            try {
                Snapshot saved = decode(GSON.fromJson(Files.readString(cache), StoredSnapshot.class));
                if (saved.updatedAt().isAfter(seed.updatedAt()) && !saved.updatedAt().isAfter(clock.instant())) {
                    this.state = new State(saved);
                }
            } catch (Exception e) {
                LOG.warn("Ignoring invalid whatif cache: {}", e.getMessage());
            }
        }
    }

    public static WhatIfService create() {
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

    public State current() {
        State current = state;
        Instant now = clock.instant();
        if (Duration.between(current.snapshot().updatedAt(), now).compareTo(REFRESH_INTERVAL) >= 0
                && (lastAttempt == null || Duration.between(lastAttempt, now).compareTo(RETRY_INTERVAL) >= 0)
                && refreshing.compareAndSet(false, true)) {
            lastAttempt = now;
            try {
                executor.execute(this::refresh);
            } catch (RuntimeException e) {
                refreshing.set(false);
                LOG.warn("Unable to start whatif refresh", e);
            }
        }
        return current;
    }

    private void refresh() {
        try {
            State previous = state;
            List<RankPpModel.Sample> fetched = fetcher.apply(sampleIds);
            State next = new State(new Snapshot("osu", clock.instant(), "osu!standard user statistics via oStella", fetched));
            // A partial response must not shrink coverage or silently replace a healthy fit.
            if (next.model().samples().size() < previous.model().samples().size() * 0.8
                    || next.model().first().rank() > Math.max(10, previous.model().first().rank() * 2)
                    || next.model().last().rank() < previous.model().last().rank() * 0.8) {
                throw new IllegalStateException("Refreshed samples lost too much coverage");
            }
            persist(next.snapshot());
            state = next;
        } catch (Exception e) {
            LOG.warn("Whatif refresh failed; retaining snapshot: {}", e.getMessage());
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
            samples = List.copyOf(samples);
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

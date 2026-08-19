package xyz.zcraft.seira.watch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MultiplayerRoomWatchService implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(MultiplayerRoomWatchService.class);

    private final Object lock = new Object();
    private final Map<String, WatchEntry> watchesByGroup = new LinkedHashMap<>();
    private final MultiplayerRoomWatchApi api;
    private final MultiplayerRoomNotifier notifier;
    private final Duration pollInterval;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public MultiplayerRoomWatchService(
            MultiplayerRoomWatchApi api,
            MultiplayerRoomNotifier notifier,
            Duration pollInterval
    ) {
        this.api = Objects.requireNonNull(api);
        this.notifier = Objects.requireNonNull(notifier);
        this.pollInterval = requirePositive(pollInterval);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "seira-multiplayer-room-watch");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Multiplayer room watch service has been closed");
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

    public RoomWatchView watch(String groupId, MultiplayerRoomVersion version, long roomId) {
        Objects.requireNonNull(groupId);
        Objects.requireNonNull(version);
        if (roomId <= 0) {
            throw new IllegalArgumentException("房间 ID 必须为正整数。");
        }
        RoomWatchSnapshot snapshot = api.getSnapshot(version, roomId);
        if (!snapshot.active()) {
            throw new IllegalStateException("该多人房间已经结束，无法开始监视。");
        }

        Set<Long> baseline = new LinkedHashSet<>();
        snapshot.completedPlays().forEach(play -> baseline.add(play.playlistItemId()));
        WatchEntry entry = new WatchEntry(version, snapshot.roomId(), snapshot.roomName(), baseline);
        synchronized (lock) {
            watchesByGroup.put(groupId, entry);
        }
        return view(entry);
    }

    public RoomWatchView stop(String groupId) {
        synchronized (lock) {
            WatchEntry removed = watchesByGroup.remove(groupId);
            return removed == null ? null : view(removed);
        }
    }

    public RoomWatchView get(String groupId) {
        synchronized (lock) {
            WatchEntry entry = watchesByGroup.get(groupId);
            return entry == null ? null : view(entry);
        }
    }

    public Set<String> activeGroupIds() {
        synchronized (lock) {
            return Set.copyOf(watchesByGroup.keySet());
        }
    }

    public void pollNow() {
        Map<RoomKey, List<WatchRef>> watchesByRoom = snapshotByRoom();
        for (Map.Entry<RoomKey, List<WatchRef>> room : watchesByRoom.entrySet()) {
            try {
                processRoom(room.getKey(), room.getValue());
            } catch (RuntimeException e) {
                LOG.error("Failed to poll multiplayer room {}", room.getKey(), e);
            }
        }
    }

    private void processRoom(RoomKey room, List<WatchRef> watches) {
        RoomWatchSnapshot snapshot = api.getSnapshot(room.version(), room.roomId());
        List<CompletedRoomPlay> completed = snapshot.completedPlays().stream()
                .sorted(Comparator
                        .comparing(CompletedRoomPlay::playedAt, Comparator.nullsLast(String::compareTo))
                        .thenComparingLong(CompletedRoomPlay::playlistItemId))
                .toList();
        Map<Long, byte[]> rendered = new HashMap<>();

        for (WatchRef watch : watches) {
            if (!isCurrent(watch)) {
                continue;
            }
            boolean allResultsSent = sendPendingResults(watch, completed, rendered);
            if (!snapshot.active() && allResultsSent && isCurrent(watch)) {
                if (notifier.sendRoomEnded(watch.groupId(), snapshot)) {
                    removeIfCurrent(watch);
                } else {
                    LOG.warn("Failed to send {} room-ended notice for room {} to group {}",
                            room.version().value(), room.roomId(), watch.groupId());
                }
            }
        }
    }

    private boolean sendPendingResults(
            WatchRef watch,
            List<CompletedRoomPlay> completed,
            Map<Long, byte[]> rendered
    ) {
        for (CompletedRoomPlay play : completed) {
            if (wasSent(watch, play.playlistItemId())) {
                continue;
            }
            try {
                byte[] image = rendered.computeIfAbsent(
                        play.playlistItemId(),
                        itemId -> api.renderResult(watch.entry().version, watch.entry().roomId, itemId)
                );
                if (!notifier.sendResult(watch.groupId(), image)) {
                    LOG.warn(
                            "Failed to send room {} playlist item {} to group {}",
                            watch.entry().roomId, play.playlistItemId(), watch.groupId()
                    );
                    return false;
                }
                markSent(watch, play.playlistItemId());
            } catch (RuntimeException e) {
                LOG.error(
                        "Failed to render/send room {} playlist item {} to group {}",
                        watch.entry().roomId, play.playlistItemId(), watch.groupId(), e
                );
                return false;
            }
        }
        return true;
    }

    private Map<RoomKey, List<WatchRef>> snapshotByRoom() {
        synchronized (lock) {
            Map<RoomKey, List<WatchRef>> result = new LinkedHashMap<>();
            watchesByGroup.forEach((groupId, entry) -> result
                    .computeIfAbsent(new RoomKey(entry.version, entry.roomId), ignored -> new ArrayList<>())
                    .add(new WatchRef(groupId, entry)));
            return result;
        }
    }

    private boolean isCurrent(WatchRef watch) {
        synchronized (lock) {
            return watchesByGroup.get(watch.groupId()) == watch.entry();
        }
    }

    private boolean wasSent(WatchRef watch, long playlistItemId) {
        synchronized (lock) {
            return watchesByGroup.get(watch.groupId()) != watch.entry()
                    || watch.entry().sentPlaylistItemIds.contains(playlistItemId);
        }
    }

    private void markSent(WatchRef watch, long playlistItemId) {
        synchronized (lock) {
            if (watchesByGroup.get(watch.groupId()) == watch.entry()) {
                watch.entry().sentPlaylistItemIds.add(playlistItemId);
            }
        }
    }

    private void removeIfCurrent(WatchRef watch) {
        synchronized (lock) {
            if (watchesByGroup.get(watch.groupId()) == watch.entry()) {
                watchesByGroup.remove(watch.groupId());
            }
        }
    }

    private void pollSafely() {
        try {
            pollNow();
        } catch (RuntimeException e) {
            LOG.error("Failed to poll multiplayer room watches", e);
        }
    }

    private static Duration requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        return duration;
    }

    private static RoomWatchView view(WatchEntry entry) {
        return new RoomWatchView(entry.version, entry.roomId, entry.roomName);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
        }
    }

    private static final class WatchEntry {
        private final MultiplayerRoomVersion version;
        private final long roomId;
        private final String roomName;
        private final Set<Long> sentPlaylistItemIds;

        private WatchEntry(
                MultiplayerRoomVersion version,
                long roomId,
                String roomName,
                Set<Long> sentPlaylistItemIds
        ) {
            this.version = version;
            this.roomId = roomId;
            this.roomName = roomName;
            this.sentPlaylistItemIds = new LinkedHashSet<>(sentPlaylistItemIds);
        }
    }

    private record WatchRef(String groupId, WatchEntry entry) {
    }

    private record RoomKey(MultiplayerRoomVersion version, long roomId) {
    }
}

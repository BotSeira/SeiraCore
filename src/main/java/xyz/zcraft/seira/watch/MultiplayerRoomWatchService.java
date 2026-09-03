package xyz.zcraft.seira.watch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MultiplayerRoomWatchService implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(MultiplayerRoomWatchService.class);

    private final Object lock = new Object();
    private final Map<String, Map<String, WatchEntry>> watchesByGroup = new LinkedHashMap<>();
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

    private static Duration requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        return duration;
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static RoomWatchView view(WatchEntry entry) {
        return new RoomWatchView(entry.version, entry.roomId, entry.roomName);
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

    public RoomWatchView watch(
            String groupId,
            String userId,
            MultiplayerRoomVersion version,
            long roomId
    ) {
        requireIdentifier(groupId, "groupId");
        requireIdentifier(userId, "userId");
        Objects.requireNonNull(version);
        if (roomId <= 0) {
            throw new IllegalArgumentException("房间 ID 必须为正整数。");
        }
        RoomKey room = new RoomKey(version, roomId);
        synchronized (lock) {
            ensureRoomAvailable(groupId, userId, room);
        }
        RoomWatchSnapshot snapshot = api.getSnapshot(version, roomId);
        if (!snapshot.active()) {
            throw new IllegalStateException("该多人房间已经结束，无法开始监视。");
        }

        Set<Long> baseline = new LinkedHashSet<>();
        snapshot.completedPlays().forEach(play -> baseline.add(play.playlistItemId()));
        WatchEntry entry = new WatchEntry(version, snapshot.roomId(), snapshot.roomName(), baseline);
        synchronized (lock) {
            ensureRoomAvailable(groupId, userId, room);
            watchesByGroup.computeIfAbsent(groupId, ignored -> new LinkedHashMap<>())
                    .put(userId, entry);
        }
        return view(entry);
    }

    public RoomWatchView stop(String groupId, String userId) {
        requireIdentifier(groupId, "groupId");
        requireIdentifier(userId, "userId");
        synchronized (lock) {
            Map<String, WatchEntry> groupWatches = watchesByGroup.get(groupId);
            if (groupWatches == null) {
                return null;
            }
            WatchEntry removed = groupWatches.remove(userId);
            if (groupWatches.isEmpty()) {
                watchesByGroup.remove(groupId);
            }
            return removed == null ? null : view(removed);
        }
    }

    public List<RoomWatchView> stopAll(String groupId) {
        requireIdentifier(groupId, "groupId");
        synchronized (lock) {
            Map<String, WatchEntry> removed = watchesByGroup.remove(groupId);
            if (removed == null) {
                return List.of();
            }
            return removed.values().stream().map(MultiplayerRoomWatchService::view).toList();
        }
    }

    public RoomWatchView get(String groupId, String userId) {
        requireIdentifier(groupId, "groupId");
        requireIdentifier(userId, "userId");
        synchronized (lock) {
            Map<String, WatchEntry> groupWatches = watchesByGroup.get(groupId);
            WatchEntry entry = groupWatches == null ? null : groupWatches.get(userId);
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
            watchesByGroup.forEach((groupId, groupWatches) -> groupWatches.forEach((userId, entry) -> result
                    .computeIfAbsent(new RoomKey(entry.version, entry.roomId), ignored -> new ArrayList<>())
                    .add(new WatchRef(groupId, userId, entry))));
            return result;
        }
    }

    private boolean isCurrent(WatchRef watch) {
        synchronized (lock) {
            return currentEntry(watch) == watch.entry();
        }
    }

    private boolean wasSent(WatchRef watch, long playlistItemId) {
        synchronized (lock) {
            return currentEntry(watch) != watch.entry()
                    || watch.entry().sentPlaylistItemIds.contains(playlistItemId);
        }
    }

    private void markSent(WatchRef watch, long playlistItemId) {
        synchronized (lock) {
            if (currentEntry(watch) == watch.entry()) {
                watch.entry().sentPlaylistItemIds.add(playlistItemId);
            }
        }
    }

    private void removeIfCurrent(WatchRef watch) {
        synchronized (lock) {
            Map<String, WatchEntry> groupWatches = watchesByGroup.get(watch.groupId());
            if (groupWatches != null && groupWatches.get(watch.userId()) == watch.entry()) {
                groupWatches.remove(watch.userId());
                if (groupWatches.isEmpty()) {
                    watchesByGroup.remove(watch.groupId());
                }
            }
        }
    }

    private WatchEntry currentEntry(WatchRef watch) {
        Map<String, WatchEntry> groupWatches = watchesByGroup.get(watch.groupId());
        return groupWatches == null ? null : groupWatches.get(watch.userId());
    }

    private void ensureRoomAvailable(String groupId, String userId, RoomKey room) {
        Map<String, WatchEntry> groupWatches = watchesByGroup.get(groupId);
        if (groupWatches == null) {
            return;
        }
        boolean watchedByAnotherUser = groupWatches.entrySet().stream()
                .anyMatch(watch -> !watch.getKey().equals(userId)
                        && watch.getValue().version == room.version()
                        && watch.getValue().roomId == room.roomId());
        if (watchedByAnotherUser) {
            throw new IllegalStateException("该多人房间已由本群其他用户监视，不能重复监视。");
        }
    }

    private void pollSafely() {
        try {
            pollNow();
        } catch (RuntimeException e) {
            LOG.error("Failed to poll multiplayer room watches", e);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
        }
    }

    private record WatchEntry(MultiplayerRoomVersion version, long roomId, String roomName,
                              Set<Long> sentPlaylistItemIds) {
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

    private record WatchRef(String groupId, String userId, WatchEntry entry) {
    }

    private record RoomKey(MultiplayerRoomVersion version, long roomId) {
    }
}

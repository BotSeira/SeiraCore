package xyz.zcraft.seira.rankguess;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RankGuessWeights {
    private static final Gson GSON = new Gson();
    private static final Logger LOG = LogManager.getLogger(RankGuessWeights.class);
    private static final Path WEIGHTS_FILE = Path.of("data", "rank-guess-weights.json");

    private static final int RECENT_USER_LIMIT = 5;
    private static final double WISH_WEIGHT = 2.5;
    private static final double RECENT_USER_WEIGHT = 0.7;
    private static final double SCORE_REPEAT_FACTOR = 0.7;

    private final Map<String, GroupState> groups = new ConcurrentHashMap<>();
    private final Path store;
    private final Object persistenceLock = new Object();

    public RankGuessWeights() {
        this(WEIGHTS_FILE);
    }

    RankGuessWeights(Path store) {
        this.store = Objects.requireNonNull(store, "store").toAbsolutePath();
        loadFromFile();
    }

    private static class GroupState {
        private final Map<Long, Integer> scoreRecords = new HashMap<>();
        private final LinkedList<Long> userRecords = new LinkedList<>();
        private final Set<Long> userWishes = new HashSet<>();
    }

    private record GroupSnapshot(Map<Long, Integer> scoreRecords, List<Long> userRecords, Set<Long> userWishes) {
    }

    private void loadFromFile() {
        if (!Files.exists(store)) {
            return;
        }
        try {
            JsonObject savedGroups = JsonParser.parseString(Files.readString(store))
                    .getAsJsonObject().getAsJsonObject("groups");
            Map<String, GroupState> restored = new HashMap<>();
            for (var entry : Objects.requireNonNull(savedGroups, "Missing groups").entrySet()) {
                if (entry.getKey().isBlank()) {
                    continue;
                }
                GroupSnapshot snapshot = Objects.requireNonNull(
                        GSON.fromJson(entry.getValue(), GroupSnapshot.class), "Missing group state");
                GroupState state = new GroupState();
                if (snapshot.scoreRecords() != null) {
                    snapshot.scoreRecords().forEach((scoreId, count) -> {
                        if (scoreId != null && scoreId > 0 && count != null && count > 0) {
                            state.scoreRecords.put(scoreId, count);
                        }
                    });
                }
                if (snapshot.userRecords() != null) {
                    for (Long userId : snapshot.userRecords()) {
                        if (userId != null && userId > 0) {
                            state.userRecords.add(userId);
                            if (state.userRecords.size() > RECENT_USER_LIMIT) {
                                state.userRecords.removeFirst();
                            }
                        }
                    }
                }
                if (snapshot.userWishes() != null) {
                    for (Long userId : snapshot.userWishes()) {
                        if (userId != null && userId > 0 && !state.userRecords.contains(userId)) {
                            state.userWishes.add(userId);
                        }
                    }
                }
                restored.put(entry.getKey(), state);
            }
            groups.putAll(restored);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed to load rank guess weights from {}, starting with empty history", store, e);
        }
    }

    public void saveToFile() {
        // Take the snapshot after acquiring the file lock so an older save cannot overwrite a newer one.
        synchronized (persistenceLock) {
            Map<String, GroupSnapshot> snapshot = new TreeMap<>();
            groups.forEach((groupId, state) -> {
                synchronized (state) {
                    snapshot.put(groupId, new GroupSnapshot(
                            new TreeMap<>(state.scoreRecords), List.copyOf(state.userRecords),
                            new TreeSet<>(state.userWishes)));
                }
            });
            JsonObject data = new JsonObject();
            data.add("groups", GSON.toJsonTree(snapshot));
            Path temporary = null;
            try {
                Files.createDirectories(store.getParent());
                temporary = Files.createTempFile(store.getParent(), "rank-guess-weights-", ".tmp");
                Files.writeString(temporary, data.toString());
                try {
                    Files.move(temporary, store, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, store, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                LOG.error("Failed to save rank guess weights to {}", store, e);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException e) {
                        LOG.warn("Failed to remove temporary rank guess weights file {}", temporary, e);
                    }
                }
            }
        }
    }

    private GroupState getGroup(String groupId) {
        return groups.computeIfAbsent(groupId, _ -> new GroupState());
    }

    public void recordScore(String groupId, long scoreId) {
        final GroupState state = getGroup(groupId);

        synchronized (state) {
            state.scoreRecords.merge(scoreId, 1, Integer::sum);
        }
        saveToFile();
    }

    public void recordUser(String groupId, long userId) {
        final GroupState state = getGroup(groupId);

        synchronized (state) {
            recordUser(state, userId);
        }
        saveToFile();
    }

    public void recordRound(String groupId, long userId, long scoreId) {
        final GroupState state = getGroup(groupId);
        synchronized (state) {
            state.scoreRecords.merge(scoreId, 1, Integer::sum);
            recordUser(state, userId);
        }
        saveToFile();
    }

    private static void recordUser(GroupState state, long userId) {
        state.userRecords.add(userId);
        if (state.userRecords.size() > RECENT_USER_LIMIT) {
            state.userRecords.removeFirst();
        }
        state.userWishes.remove(userId);
    }

    public boolean recentPicked(String groupId, long userId) {
        final GroupState state = getGroup(groupId);

        synchronized (state) {
            return state.userRecords.contains(userId);
        }
    }

    public WishResult tryWish(String groupId, long userId) {
        final GroupState state = getGroup(groupId);

        synchronized (state) {
            if (state.userWishes.contains(userId)) {
                return WishResult.ALREADY_WISHED;
            }

            if (state.userRecords.contains(userId)) {
                return WishResult.RECENTLY_PICKED;
            }

            state.userWishes.add(userId);
        }
        saveToFile();
        return WishResult.SUCCESS;
    }

    public JsonObject generateWeights(String groupId) {
        final GroupState state = getGroup(groupId);

        final Map<Long, Double> users = new HashMap<>();
        final Map<Long, Double> scores = new HashMap<>();

        synchronized (state) {
            for (Long wishedId : state.userWishes) {
                users.put(wishedId, WISH_WEIGHT);
            }

            for (Long pickedId : state.userRecords) {
                users.put(pickedId, RECENT_USER_WEIGHT);
            }

            state.scoreRecords.forEach((scoreId, count) ->
                    scores.put(
                            scoreId,
                            Math.pow(SCORE_REPEAT_FACTOR, count)
                    )
            );
        }

        final JsonObject weights = new JsonObject();

        weights.add("users", GSON.toJsonTree(users));
        weights.add("scores", GSON.toJsonTree(scores));

        return weights;
    }

    public List<Long> getGroupWishes(String groupId) {
        final GroupState state = getGroup(groupId);

        synchronized (state) {
            return List.copyOf(state.userWishes);
        }
    }

    public List<Long> getGroupUserRecords(String groupId) {
        final GroupState state = getGroup(groupId);

        synchronized (state) {
            return List.copyOf(state.userRecords);
        }
    }

    public Map<Long, Integer> getGroupScoreRecords(String groupId) {
        final GroupState state = getGroup(groupId);

        synchronized (state) {
            return Map.copyOf(state.scoreRecords);
        }
    }
}

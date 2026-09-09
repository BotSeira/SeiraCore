package xyz.zcraft.seira.rankguess;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.db.RankGuessRecordStore;
import xyz.zcraft.seira.db.UserDataStore;

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

    private static final double SCORE_REPEAT_FACTOR = 0.10;

    private final Map<String, GroupState> groups = new ConcurrentHashMap<>();
    private final Path store;
    private final Object persistenceLock = new Object();

    public RankGuessWeights() {
        this(WEIGHTS_FILE);
    }

    private double getWishFactor(String groupId) {
        int playerCount = UserDataStore.findBoundUidsByGroup(groupId).size();

        final double value = 1.25 + playerCount / 100.0;

        return Math.clamp(value, 1.25, 2.0);
    }

    RankGuessWeights(Path store) {
        this.store = Objects.requireNonNull(store, "store").toAbsolutePath();
        loadFromFile();
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
                if (snapshot.userWishes() != null) {
                    for (Long userId : snapshot.userWishes()) {
                        if (userId != null && userId > 0) {
                            state.userWishes.add(userId);
                        }
                    }
                }
                if (snapshot.scoreWishes() != null) {
                    for (Long scoreId : snapshot.scoreWishes()) {
                        if (scoreId != null && scoreId > 0) {
                            state.scoreWishes.add(scoreId);
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
                            new TreeMap<>(state.scoreRecords),
                            new TreeSet<>(state.userWishes),
                            new TreeSet<>(state.scoreWishes))
                    );
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

    public void recordRound(String groupId, long userId, long scoreId) {
        final GroupState state = getGroup(groupId);
        synchronized (state) {
            state.scoreRecords.merge(scoreId, 1, Integer::sum);

            state.userWishes.remove(userId);

            state.scoreWishes.remove(scoreId);
        }
        saveToFile();
    }

    public RankGuessGameService.WishResult tryWish(String groupId, long userId) {
        final GroupState state = getGroup(groupId);
        final Map<Long, Integer> gamesSincePicked = RankGuessRecordStore.getGamesSincePicked(groupId);

        synchronized (state) {
            if (state.userWishes.contains(userId)) {
                return RankGuessGameService.WishResult.ALREADY_WISHED;
            }

//            if (state.userRecords.contains(userId)) {
//                return RankGuessGameService.WishResult.RECENTLY_PICKED;
//            }

            state.userWishes.add(userId);
        }
        saveToFile();
        return RankGuessGameService.WishResult.SUCCESS;
    }

    public RankGuessGameService.WishResult tryWishScore(String groupId, long scoreId) {
        final GroupState state = getGroup(groupId);

        synchronized (state) {
            if (state.scoreWishes.contains(scoreId)) {
                return RankGuessGameService.WishResult.ALREADY_WISHED;
            }

            state.scoreWishes.add(scoreId);
        }
        saveToFile();
        return RankGuessGameService.WishResult.SUCCESS;
    }

    public JsonObject generateWeights(String groupId) {
        final GroupState state = getGroup(groupId);

        final Map<Long, Double> users = generateUserWeights(groupId, state);

        final Map<Long, Double> scores = new HashMap<>();

        synchronized (state) {
            for (Long wishedId : state.scoreWishes) {
                scores.put(wishedId, 7.50);
            }

            state.scoreRecords.forEach(
                    (scoreId, count) -> scores.putIfAbsent(scoreId, Math.pow(SCORE_REPEAT_FACTOR, count))
            );
        }

        final JsonObject weights = new JsonObject();

        weights.add("users", GSON.toJsonTree(users));
        weights.add("scores", GSON.toJsonTree(scores));

        return weights;
    }

    public record Probability(
            double weight,
            double chance
    ){}

    public Probability getProbability(String groupId, long boundUid) {
        final GroupState state = getGroup(groupId);

        final Map<Long, Double> users = generateUserWeights(groupId, state);

        final double weight = users.getOrDefault(boundUid, 1.0);

        double accumulation = 0;

        for (Long uid : UserDataStore.findBoundUidsByGroup(groupId)) {
            accumulation += users.getOrDefault(uid, 1.0);
        }

        if (accumulation <= 0) {
            throw new IllegalStateException("Error calculating probability for " + groupId + " " + boundUid);
        }

        return new Probability(weight, weight / accumulation);
    }

    private static class GroupState {
        private final Map<Long, Integer> scoreRecords = new HashMap<>();
        private final Set<Long> userWishes = new HashSet<>();
        private final Set<Long> scoreWishes = new HashSet<>();
    }

    private record GroupSnapshot(Map<Long, Integer> scoreRecords,
                                 Set<Long> userWishes,
                                 Set<Long> scoreWishes) {
    }

    private static final double JUST_PICKED_WEIGHT = 0.05;
    private static final double MAX_OVERDUE_WEIGHT = 2.00;
    private static final double NEVER_PICKED_WEIGHT = 2.50;

    private Map<Long, Double> generateUserWeights(String groupId, GroupState state) {
        final List<Long> boundUsers = UserDataStore.findBoundUidsByGroup(groupId);
        final Map<Long, Integer> gamesSincePicked = RankGuessRecordStore.getGamesSincePicked(groupId);

        final int playerCount = boundUsers.size();
        final double wishFactor = getWishFactor(groupId);

        final Set<Long> wishes;

        synchronized (state) {
            wishes = Set.copyOf(state.userWishes);
        }

        final Map<Long, Double> users = new HashMap<>();

        for (Long uid : boundUsers) {
            double weight = getPickWeight(gamesSincePicked.get(uid), playerCount);

            if (wishes.contains(uid)) {
                weight *= wishFactor;
            }

            users.put(uid, weight);
        }

        return users;
    }

    private static double getPickWeight(Integer gamesSincePicked, int playerCount) {
        if (gamesSincePicked == null) {
            return NEVER_PICKED_WEIGHT;
        }

        if (playerCount <= 0) {
            return 1.0;
        }

        double progress = gamesSincePicked / (double) playerCount;

        return Math.clamp(
                JUST_PICKED_WEIGHT + progress * 1.5,
                JUST_PICKED_WEIGHT,
                MAX_OVERDUE_WEIGHT
        );
    }
}

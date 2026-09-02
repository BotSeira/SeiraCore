package xyz.zcraft.seira.rankguess;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RankGuessWeights {
    private static final Gson GSON = new Gson();

    private static final int RECENT_USER_LIMIT = 5;
    private static final double WISH_WEIGHT = 2.5;
    private static final double RECENT_USER_WEIGHT = 0.7;
    private static final double SCORE_REPEAT_FACTOR = 0.7;

    private final Map<String, GroupState> groups = new ConcurrentHashMap<>();

    private static class GroupState {
        private final Map<Long, Integer> scoreRecords = new HashMap<>();
        private final LinkedList<Long> userRecords = new LinkedList<>();
        private final Set<Long> userWishes = new HashSet<>();
    }

    private GroupState getGroup(String groupId) {
        return groups.computeIfAbsent(groupId, _ -> new GroupState());
    }

    public void recordScore(String groupId, long scoreId) {
        final GroupState state = getGroup(groupId);

        synchronized (state) {
            state.scoreRecords.merge(scoreId, 1, Integer::sum);
        }
    }

    public void recordUser(String groupId, long userId) {
        final GroupState state = getGroup(groupId);

        synchronized (state) {
            state.userRecords.add(userId);

            if (state.userRecords.size() > RECENT_USER_LIMIT) {
                state.userRecords.removeFirst();
            }

            state.userWishes.remove(userId);
        }
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

            return WishResult.SUCCESS;
        }
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
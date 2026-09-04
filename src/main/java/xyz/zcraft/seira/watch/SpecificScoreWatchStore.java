package xyz.zcraft.seira.watch;

import java.util.List;

public interface SpecificScoreWatchStore {
    List<SpecificScoreWatchState> loadAll();

    void save(SpecificScoreWatchState state);

    boolean delete(String groupId);

    void updateLastScoreId(String groupId, long userId, long scoreId);
}

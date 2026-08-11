package xyz.zcraft.seira.watch;

import java.util.List;

public interface SpecificScoreWatchStore {
    List<SpecificScoreWatchState> loadAll();

    void save(SpecificScoreWatchState state);

    boolean delete(String groupId);

    void updateLastScoreId(String groupId, long userId, long scoreId);

    static SpecificScoreWatchStore none() {
        return new SpecificScoreWatchStore() {
            @Override
            public List<SpecificScoreWatchState> loadAll() {
                return List.of();
            }

            @Override
            public void save(SpecificScoreWatchState state) {
            }

            @Override
            public boolean delete(String groupId) {
                return false;
            }

            @Override
            public void updateLastScoreId(String groupId, long userId, long scoreId) {
            }
        };
    }
}

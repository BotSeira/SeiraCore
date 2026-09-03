package xyz.zcraft.seira.watch;

import xyz.zcraft.seira.db.UserDataStore;

import java.util.List;

public final class SqliteSpecificScoreWatchStore implements SpecificScoreWatchStore {
    @Override
    public List<SpecificScoreWatchState> loadAll() {
        return UserDataStore.findAllSpecificScoreWatches();
    }

    @Override
    public void save(SpecificScoreWatchState state) {
        UserDataStore.saveSpecificScoreWatch(state);
    }

    @Override
    public boolean delete(String groupId) {
        return UserDataStore.removeSpecificScoreWatch(groupId);
    }

    @Override
    public void updateLastScoreId(String groupId, long userId, long scoreId) {
        UserDataStore.updateSpecificScoreWatchCursor(groupId, userId, scoreId);
    }
}

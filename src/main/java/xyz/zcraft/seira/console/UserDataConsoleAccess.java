package xyz.zcraft.seira.console;

import xyz.zcraft.seira.binding.UserDataStore;

public final class UserDataConsoleAccess implements ConsoleDataAccess {
    @Override
    public UserDataStore.QueryResult query(String sql, int maxRows) {
        return UserDataStore.queryReadOnly(sql, maxRows);
    }

    @Override
    public int boundUsers() {
        return UserDataStore.countBoundUser();
    }

    @Override
    public int groups() {
        return UserDataStore.countGroups();
    }
}

package xyz.zcraft.seira.console;

import xyz.zcraft.seira.binding.UserDataStore;

public interface ConsoleDataAccess {
    UserDataStore.QueryResult query(String sql, int maxRows);

    int boundUsers();

    int groups();
}

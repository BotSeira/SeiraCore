package xyz.zcraft.seira.console;

import xyz.zcraft.seira.db.SqliteDatabase;
import xyz.zcraft.seira.db.UserDataStore;

public final class UserDataConsoleAccess implements ConsoleDataAccess {
    @Override
    public SqliteDatabase.QueryResult query(String sql, int maxRows) {
        return SqliteDatabase.queryReadOnly(sql, maxRows);
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

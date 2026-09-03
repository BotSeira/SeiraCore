package xyz.zcraft.seira.console;

import xyz.zcraft.seira.db.SqliteDatabase;

public interface ConsoleDataAccess {
    SqliteDatabase.QueryResult query(String sql, int maxRows);

    int boundUsers();

    int groups();
}

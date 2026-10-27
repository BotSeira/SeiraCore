package xyz.zcraft.seira.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class McServerStore {
    public static void bindGroup(String groupId, String serverId, String serverSecret) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                INSERT INTO minecraft_servers(group_id, server_id, server_secret, updated_at)
                VALUES(?, ?, ?, ?)
                ON CONFLICT(group_id) DO UPDATE SET
                    server_id = excluded.server_id,
                    server_secret = excluded.server_secret,
                    updated_at = excluded.updated_at
                """;

        try (Connection conn = SqliteDatabase.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            stmt.setString(2, serverId);
            stmt.setString(3, serverSecret);
            stmt.setLong(4, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to bind group", e);
        }
    }

    public static void unbindGroup(String groupId) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                DELETE FROM minecraft_servers WHERE group_id = ?;
                """;

        try (Connection conn = SqliteDatabase.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to unbind group", e);
        }
    }

    public static String getGroupBoundId(String groupId) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                SELECT server_id FROM minecraft_servers
                WHERE group_id = ?;
                """;

        try (var conn = SqliteDatabase.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, groupId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("server_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get group bound ID", e);
        }
        return null;
    }

    public static String getBoundGroup(String serverId, String serverSecret) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                SELECT group_id FROM minecraft_servers
                WHERE server_id = ? AND server_secret = ?;
                """;

        try (var conn = SqliteDatabase.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, serverId);
            stmt.setString(2, serverSecret);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("group_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get bound group", e);
        }
        return null;
    }

    public static boolean validateSecret(String serverId, String secret) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                SELECT server_secret FROM minecraft_servers
                WHERE server_id = ?;
                """;

        try (var conn = SqliteDatabase.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, serverId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("server_secret").equals(secret);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to validate secret", e);
        }
        return false;
    }

    public static void createTablesIfNeeded(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS minecraft_servers (
                        group_id          TEXT PRIMARY KEY,
                        server_id         TEXT NOT NULL,
                        server_secret     TEXT NOT NULL,
                        updated_at        BIGINT NOT NULL
                    );
                    """);
        }
    }
}

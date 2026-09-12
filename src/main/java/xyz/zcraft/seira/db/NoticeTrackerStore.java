package xyz.zcraft.seira.db;

import xyz.zcraft.seira.data.Notice;

import java.sql.*;

public class NoticeTrackerStore {
    public static void updateGroupTracker(String groupId, long lastNotifiedId) {
        SqliteDatabase.ensureInitialized();
        long now = System.currentTimeMillis();
        String sql = """
                INSERT INTO group_notice_tracker(group_id, last_notified_id, last_notified_at)
                VALUES(?, ?, ?)
                ON CONFLICT(group_id) DO UPDATE SET
                    last_notified_id = excluded.last_notified_id,
                    last_notified_at = excluded.last_notified_at
                """;
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupId);
            statement.setLong(2, lastNotifiedId);
            statement.setLong(3, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update group notice tracker", e);
        }
    }

    public static void updatePrivateTracker(String openId, long lastNotifiedId) {
        SqliteDatabase.ensureInitialized();
        long now = System.currentTimeMillis();
        String sql = """
                INSERT INTO private_notice_tracker(open_id, last_notified_id, last_notified_at)
                VALUES(?, ?, ?)
                ON CONFLICT(open_id) DO UPDATE SET
                    last_notified_id = excluded.last_notified_id,
                    last_notified_at = excluded.last_notified_at
                """;
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, openId);
            statement.setLong(2, lastNotifiedId);
            statement.setLong(3, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update private notice tracker", e);
        }
    }

    public static NoticeTrackState getGroupTracker(String groupId) {
        SqliteDatabase.ensureInitialized();

        String sql = """
            SELECT last_notified_id, last_notified_at
            FROM group_notice_tracker
            WHERE group_id = ?
            """;

        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, groupId);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return NoticeTrackState.EMPTY;
                }

                return new NoticeTrackState(
                        result.getLong("last_notified_id"),
                        result.getLong("last_notified_at")
                );
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get group notice tracker", e);
        }
    }

    public static NoticeTrackState getPrivateTracker(String openId) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                SELECT last_notified_id, last_notified_at
                FROM private_notice_tracker
                WHERE open_id = ?;
                """;
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, openId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return NoticeTrackState.EMPTY;
                }

                return new NoticeTrackState(
                        result.getLong("last_notified_id"),
                        result.getLong("last_notified_at")
                );
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get private notice tracker", e);
        }
    }

    public static void createTablesIfNeeded(Connection connection) throws SQLException {
        String groupTrackerSql = """
                CREATE TABLE IF NOT EXISTS group_notice_tracker (
                    group_id          TEXT PRIMARY KEY,
                    last_notified_id  INTEGER NOT NULL,
                    last_notified_at  INTEGER NOT NULL
                );
                """;

        String privateTrackerSql = """
                CREATE TABLE IF NOT EXISTS private_notice_tracker (
                    open_id           TEXT PRIMARY KEY,
                    last_notified_id  INTEGER NOT NULL,
                    last_notified_at  INTEGER NOT NULL
                );
                """;
        try (Statement statement = connection.createStatement()) {
            statement.execute(groupTrackerSql);
            statement.execute(privateTrackerSql);
        }
    }

    public record NoticeTrackState(
            long lastNotifiedId,
            long lastNotifiedAt
    ) {
        public static final NoticeTrackState EMPTY =
                new NoticeTrackState(0, 0);

        public boolean hasNotified(long noticeId) {
            return lastNotifiedId >= noticeId;
        }

        public boolean hasNotified(Notice notice) {
            return hasNotified(notice.id());
        }

        public boolean hasNeverNotified() {
            return lastNotifiedAt == 0;
        }
    }
}

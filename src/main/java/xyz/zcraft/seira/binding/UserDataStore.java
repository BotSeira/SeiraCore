package xyz.zcraft.seira.binding;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.data.OsuToken;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class UserDataStore {
    private static final Logger LOG = LogManager.getLogger(UserDataStore.class);
    private static final Object INIT_LOCK = new Object();

    private static volatile String jdbcUrl;

    public static void init(String sqlitePath) {
        synchronized (INIT_LOCK) {
            if (jdbcUrl != null) {
                return;
            }
            try {
                Path dbPath = Path.of(sqlitePath).toAbsolutePath().normalize();
                Path parent = dbPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                jdbcUrl = "jdbc:sqlite:" + dbPath;
                createTablesIfNeeded();
                LOG.info("SQLite binding store initialized at {}", dbPath);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize sqlite store: " + sqlitePath, e);
            }
        }
    }

    public static void bind(String openId, long osuUid) {
        ensureInitialized();
        long now = System.currentTimeMillis();
        String sql = """
                INSERT INTO user_bindings(open_id, osu_uid, created_at, updated_at)
                VALUES(?, ?, ?, ?)
                ON CONFLICT(open_id) DO UPDATE SET
                    osu_uid = excluded.osu_uid,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, openId);
            statement.setLong(2, osuUid);
            statement.setLong(3, now);
            statement.setLong(4, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to persist binding", e);
        }
    }

    public static void storeToken(String openId, OsuToken token) {
        ensureInitialized();
        String sql = """
                INSERT INTO token_store(open_id, access_token, refresh_token, expires_in, refreshed_at)
                VALUES(?, ?, ?, ?, ?)
                ON CONFLICT(open_id) DO UPDATE SET
                    access_token = excluded.access_token,
                    refresh_token = excluded.refresh_token,
                    expires_in = excluded.expires_in,
                    refreshed_at = excluded.refreshed_at
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, openId);
            statement.setString(2, token.accessToken());
            statement.setString(3, token.refreshToken());
            statement.setLong(4, token.expiresIn());
            statement.setLong(5, token.refreshedAt());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store token", e);
        }
    }

    public static void storeUserInfo(long osuId, String username) {
        ensureInitialized();
        String sql = """
                INSERT INTO user_info(uid, username)
                VALUES(?, ?)
                ON CONFLICT(uid) DO UPDATE SET
                    username = excluded.username
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, osuId);
            statement.setString(2, username);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store user info", e);
        }
    }

    public static void removeUserInfo(long osuId) {
        ensureInitialized();
        String sql = """
                DELETE FROM user_info
                WHERE uid = ?
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, osuId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete user info", e);
        }
    }

    public static Optional<String> findUsername(long osuId) {
        ensureInitialized();
        String sql = """
                SELECT username FROM user_info
                WHERE uid = ?
        """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, osuId);
            final ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                return Optional.of(resultSet.getString("username"));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete user info", e);
        }
    }

    public static Long findBoundUid(String openId) {
        ensureInitialized();
        String sql = "SELECT osu_uid FROM user_bindings WHERE open_id = ?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, openId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong("osu_uid");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query binding", e);
        }
        return null;
    }

    public static OsuToken findOsuToken(String openId) {
        ensureInitialized();
        String sql = "SELECT access_token, refresh_token, expires_in, refreshed_at FROM token_store WHERE open_id = ?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, openId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new OsuToken(
                            resultSet.getString("access_token"),
                            resultSet.getString("refresh_token"),
                            resultSet.getLong("expires_in"),
                            resultSet.getLong("refreshed_at")
                    );
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query binding", e);
        }
        return null;
    }

    public static List<Long> findFollower(long uid) {
        ensureInitialized();
        List<Long> followers = new ArrayList<>();
        String sql = "SELECT self FROM user_follows WHERE followed = ?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, uid);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    long followerId = resultSet.getLong("self");
                    followers.add(followerId);
                }
            }
            return followers;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query binding", e);
        }
    }

    public static void storeFollowed(long selfId, long followed) {
        ensureInitialized();
        String sql = """
                INSERT INTO user_follows(self, followed)
                VALUES(?, ?)
                ON CONFLICT(self, followed) DO NOTHING
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setLong(1, selfId);
            statement.setLong(2, followed);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store follow", e);
        }
    }

    public static void removeFollowed(long selfId, long followed) {
        ensureInitialized();
        String sql = """
                DELETE FROM user_follows
                WHERE self = ?
                AND followed = ?;
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setLong(1, selfId);
            statement.setLong(2, followed);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove follow", e);
        }
    }

    public static boolean haveFollowed(long selfId, long followed) {
        ensureInitialized();
        String sql = """
                SELECT * FROM user_follows
                WHERE self = ?
                AND followed = ?;
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setLong(1, selfId);
            statement.setLong(2, followed);

            final ResultSet resultSet = statement.executeQuery();

            return resultSet.next();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to lookup follow", e);
        }
    }

    public static boolean unbind(String openId) {
        ensureInitialized();
        String bindingSql = "DELETE FROM user_bindings WHERE open_id = ?";
        String tokenSql = "DELETE FROM token_store WHERE open_id = ?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement bindingStatement = connection.prepareStatement(bindingSql);
             PreparedStatement tokenStatement = connection.prepareStatement(tokenSql)) {
            connection.setAutoCommit(false);

            bindingStatement.setString(1, openId);
            tokenStatement.setString(1, openId);

            try {
                tokenStatement.executeUpdate();
                boolean deleted = bindingStatement.executeUpdate() > 0;
                connection.commit();
                return deleted;
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }

                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete binding", e);
        }
    }

    public static void upsertGroupMember(String groupId, String openId) {
        ensureInitialized();
        long now = System.currentTimeMillis();
        String sql = """
                INSERT INTO group_members(group_id, open_id, updated_at)
                VALUES(?, ?, ?)
                ON CONFLICT(group_id, open_id) DO UPDATE SET
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupId);
            statement.setString(2, openId);
            statement.setLong(3, now);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to persist group member", e);
        }
    }

    public static int clearGroupMember(String openId) {
        ensureInitialized();
        String sql = "DELETE FROM group_members WHERE open_id = ?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, openId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear group member", e);
        }
    }

    public static List<Long> findBoundUidsByGroup(String groupId) {
        ensureInitialized();
        String sql = """
                SELECT DISTINCT ub.osu_uid
                FROM group_members gm
                JOIN user_bindings ub
                 ON ub.open_id = gm.open_id
                WHERE gm.group_id = ?
                ORDER BY ub.osu_uid
                """;
        List<Long> uids = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    uids.add(resultSet.getLong("osu_uid"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query group bindings", e);
        }
        return uids;
    }

    private static void createTablesIfNeeded() throws SQLException {
        String bindingSql = """
                CREATE TABLE IF NOT EXISTS user_bindings (
                    open_id TEXT NOT NULL,
                    osu_uid BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY(open_id)
                )
                """;
        String groupMemberSql = """
                CREATE TABLE IF NOT EXISTS group_members (
                    group_id TEXT NOT NULL,
                    open_id TEXT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY(group_id, open_id)
                )
                """;
        String tokenStoreSql = """
                CREATE TABLE IF NOT EXISTS token_store (
                    open_id TEXT NOT NULL,
                    access_token TEXT NOT NULL,
                    refresh_token TEXT NOT NULL,
                    expires_in BIGINT NOT NULL,
                    refreshed_at BIGINT NOT NULL,
                    PRIMARY KEY(open_id)
                )
                """;
        String followSql = """
                CREATE TABLE IF NOT EXISTS user_follows (
                    self BIGINT NOT NULL,
                    followed BIGINT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                
                    PRIMARY KEY(self, followed)
                )
                """;
        String followIndexSql = """
                CREATE INDEX IF NOT EXISTS idx_followed_id ON user_follows(followed)
                """;
        String userInfoSql = """
                CREATE TABLE IF NOT EXISTS user_info (
                    uid BIGINT NOT NULL,
                    username TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                
                    PRIMARY KEY(uid)
                )
                """;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute(bindingSql);
            statement.execute(groupMemberSql);
            statement.execute(tokenStoreSql);
            statement.execute(followSql);
            statement.execute(followIndexSql);
            statement.execute(userInfoSql);
        }
    }

    private static void ensureInitialized() {
        if (jdbcUrl == null) {
            throw new IllegalStateException("UserBindingStore is not initialized");
        }
    }
}


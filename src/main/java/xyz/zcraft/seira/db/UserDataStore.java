package xyz.zcraft.seira.db;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xyz.zcraft.seira.api.data.OsuToken;
import xyz.zcraft.seira.discord.DiscordBridgeMapping;
import xyz.zcraft.seira.util.OsuAuthHelper;
import xyz.zcraft.seira.watch.SpecificScoreWatchState;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class UserDataStore {
    private static final Logger LOG = LogManager.getLogger(UserDataStore.class);

    public static void bind(String openId, long osuUid) {
        SqliteDatabase.ensureInitialized();
        long now = System.currentTimeMillis();
        String sql = """
                INSERT INTO user_bindings(open_id, osu_uid, created_at, updated_at)
                VALUES(?, ?, ?, ?)
                ON CONFLICT(open_id) DO UPDATE SET
                    osu_uid = excluded.osu_uid,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = SqliteDatabase.getConnection();
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
        SqliteDatabase.ensureInitialized();
        String sql = """
                INSERT INTO token_store(open_id, access_token, refresh_token, expires_in, refreshed_at)
                VALUES(?, ?, ?, ?, ?)
                ON CONFLICT(open_id) DO UPDATE SET
                    access_token = excluded.access_token,
                    refresh_token = excluded.refresh_token,
                    expires_in = excluded.expires_in,
                    refreshed_at = excluded.refreshed_at
                """;
        try (Connection connection = SqliteDatabase.getConnection();
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

    public static void removeToken(String openId) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                DELETE FROM token_store
                WHERE open_id = ?
                """;
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, openId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove token", e);
        }
    }

    public static void storeUserInfo(long osuId, String username) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                INSERT INTO user_info(uid, username)
                VALUES(?, ?)
                ON CONFLICT(uid) DO UPDATE SET
                    username = excluded.username
                """;
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, osuId);
            statement.setString(2, username);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store user info", e);
        }
    }

    public static Optional<String> findUsername(long osuId) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                        SELECT username FROM user_info
                        WHERE uid = ?
                """;
        try (Connection connection = SqliteDatabase.getConnection();
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
        SqliteDatabase.ensureInitialized();
        String sql = "SELECT osu_uid FROM user_bindings WHERE open_id = ?";
        try (Connection connection = SqliteDatabase.getConnection();
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
        SqliteDatabase.ensureInitialized();
        String sql = "SELECT access_token, refresh_token, expires_in, refreshed_at FROM token_store WHERE open_id = ?";
        try (Connection connection = SqliteDatabase.getConnection();
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

    public static List<OsuAuthHelper.TokenStore> getAllOsuTokens() {
        SqliteDatabase.ensureInitialized();
        List<OsuAuthHelper.TokenStore> tokens = new LinkedList<>();
        String sql = "SELECT open_id, access_token, refresh_token, expires_in, refreshed_at FROM token_store";
        try (Connection connection = SqliteDatabase.getConnection();
             Statement statement = connection.createStatement();
             final ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                tokens.add(
                        new OsuAuthHelper.TokenStore(
                                rs.getString("open_id"),
                                new OsuToken(
                                        rs.getString("access_token"),
                                        rs.getString("refresh_token"),
                                        rs.getLong("expires_in"),
                                        rs.getLong("refreshed_at")
                                )
                        )
                );
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query binding", e);
        }
        return tokens;
    }

    public static List<Long> findFollower(long uid) {
        SqliteDatabase.ensureInitialized();
        List<Long> followers = new ArrayList<>();
        String sql = "SELECT self FROM user_follows WHERE followed = ?";
        try (Connection connection = SqliteDatabase.getConnection();
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
        SqliteDatabase.ensureInitialized();
        String sql = """
                INSERT INTO user_follows(self, followed)
                VALUES(?, ?)
                ON CONFLICT(self, followed) DO NOTHING
                """;
        try (Connection connection = SqliteDatabase.getConnection()) {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setLong(1, selfId);
            statement.setLong(2, followed);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store follow", e);
        }
    }

    public static void removeFollowed(long selfId, long followed) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                DELETE FROM user_follows
                WHERE self = ?
                AND followed = ?;
                """;
        try (Connection connection = SqliteDatabase.getConnection()) {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setLong(1, selfId);
            statement.setLong(2, followed);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove follow", e);
        }
    }

    public static int clearFollowed(long selfId) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                DELETE FROM user_follows
                WHERE self = ?;
                """;
        try (Connection connection = SqliteDatabase.getConnection()) {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setLong(1, selfId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear follow", e);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean haveFollowed(long selfId, long followed) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                SELECT * FROM user_follows
                WHERE self = ?
                AND followed = ?;
                """;
        try (Connection connection = SqliteDatabase.getConnection()) {
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
        SqliteDatabase.ensureInitialized();
        String bindingSql = "DELETE FROM user_bindings WHERE open_id = ?";
        String tokenSql = "DELETE FROM token_store WHERE open_id = ?";
        try (Connection connection = SqliteDatabase.getConnection();
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
        SqliteDatabase.ensureInitialized();
        long now = System.currentTimeMillis();
        String sql = """
                INSERT INTO group_members(group_id, open_id, updated_at)
                VALUES(?, ?, ?)
                ON CONFLICT(group_id, open_id) DO UPDATE SET
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = SqliteDatabase.getConnection();
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
        SqliteDatabase.ensureInitialized();
        String sql = "DELETE FROM group_members WHERE open_id = ?";
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, openId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear group member", e);
        }
    }

    public static List<String> findAllGroupMembers(String groupId) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                SELECT open_id
                FROM group_members
                WHERE group_id = ?;
        """;
        List<String> groupMembers = new LinkedList<>();
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    groupMembers.add(resultSet.getString("open_id"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query group members", e);
        }
        return groupMembers;
    }

    public static List<Long> findBoundUidsByGroup(String groupId) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                SELECT DISTINCT ub.osu_uid
                FROM group_members gm
                JOIN user_bindings ub
                 ON ub.open_id = gm.open_id
                WHERE gm.group_id = ?
                ORDER BY ub.osu_uid
                """;
        List<Long> uids = new ArrayList<>();
        try (Connection connection = SqliteDatabase.getConnection();
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

    public static boolean isGroupMember(String groupId, String openId) {
        SqliteDatabase.ensureInitialized();
        String sql = "SELECT 1 FROM group_members WHERE group_id = ? AND open_id = ?";
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupId);
            statement.setString(2, openId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query group member", e);
        }
    }

    public static Optional<String> findGroupOpenIdByUid(String groupId, long osuUid) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                SELECT gm.open_id
                FROM group_members gm
                JOIN user_bindings ub
                  ON ub.open_id = gm.open_id
                WHERE gm.group_id = ?
                  AND ub.osu_uid = ?
                ORDER BY gm.updated_at DESC
                LIMIT 1
                """;
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupId);
            statement.setLong(2, osuUid);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(resultSet.getString("open_id"))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query group binding", e);
        }
    }

    public static void upsertDiscordBridge(DiscordBridgeMapping mapping) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                INSERT INTO discord_bridges(group_id, guild_id, channel_id, updated_at)
                VALUES(?, ?, ?, ?)
                ON CONFLICT(group_id) DO UPDATE SET
                    guild_id = excluded.guild_id,
                    channel_id = excluded.channel_id,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, mapping.groupId());
            statement.setString(2, mapping.guildId());
            statement.setString(3, mapping.channelId());
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to persist Discord bridge", e);
        }
    }

    public static boolean removeDiscordBridge(String groupId) {
        SqliteDatabase.ensureInitialized();
        String sql = "DELETE FROM discord_bridges WHERE group_id = ?";
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove Discord bridge", e);
        }
    }

    public static List<DiscordBridgeMapping> findAllDiscordBridges() {
        SqliteDatabase.ensureInitialized();
        String sql = "SELECT group_id, guild_id, channel_id FROM discord_bridges ORDER BY group_id";
        List<DiscordBridgeMapping> mappings = new ArrayList<>();
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                mappings.add(new DiscordBridgeMapping(
                        resultSet.getString("group_id"),
                        resultSet.getString("guild_id"),
                        resultSet.getString("channel_id")
                ));
            }
            return List.copyOf(mappings);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load Discord bridges", e);
        }
    }

    public static void saveSpecificScoreWatch(SpecificScoreWatchState state) {
        SqliteDatabase.ensureInitialized();
        String upsertWatch = """
                INSERT INTO specific_score_watches(group_id, updated_at)
                VALUES(?, ?)
                ON CONFLICT(group_id) DO UPDATE SET updated_at = excluded.updated_at
                """;
        String deleteUsers = "DELETE FROM specific_score_watch_users WHERE group_id = ?";
        String deleteBeatmaps = "DELETE FROM specific_score_watch_beatmaps WHERE group_id = ?";
        String insertUser = """
                INSERT INTO specific_score_watch_users(group_id, user_id, last_score_id)
                VALUES(?, ?, ?)
                """;
        String insertBeatmap = """
                INSERT INTO specific_score_watch_beatmaps(group_id, beatmap_id)
                VALUES(?, ?)
                """;
        try (Connection connection = SqliteDatabase.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement watchStatement = connection.prepareStatement(upsertWatch);
                 PreparedStatement deleteUsersStatement = connection.prepareStatement(deleteUsers);
                 PreparedStatement deleteBeatmapsStatement = connection.prepareStatement(deleteBeatmaps);
                 PreparedStatement userStatement = connection.prepareStatement(insertUser);
                 PreparedStatement beatmapStatement = connection.prepareStatement(insertBeatmap)) {
                watchStatement.setString(1, state.groupId());
                watchStatement.setLong(2, System.currentTimeMillis());
                watchStatement.executeUpdate();

                deleteUsersStatement.setString(1, state.groupId());
                deleteUsersStatement.executeUpdate();
                deleteBeatmapsStatement.setString(1, state.groupId());
                deleteBeatmapsStatement.executeUpdate();

                for (long userId : state.userIds()) {
                    userStatement.setString(1, state.groupId());
                    userStatement.setLong(2, userId);
                    Long lastScoreId = state.lastScoreIds().get(userId);
                    if (lastScoreId == null) {
                        userStatement.setNull(3, Types.BIGINT);
                    } else {
                        userStatement.setLong(3, lastScoreId);
                    }
                    userStatement.addBatch();
                }
                userStatement.executeBatch();

                for (long beatmapId : state.beatmapIds()) {
                    beatmapStatement.setString(1, state.groupId());
                    beatmapStatement.setLong(2, beatmapId);
                    beatmapStatement.addBatch();
                }
                beatmapStatement.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to persist specific score watch", e);
        }
    }

    public static boolean removeSpecificScoreWatch(String groupId) {
        SqliteDatabase.ensureInitialized();
        try (Connection connection = SqliteDatabase.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement users = connection.prepareStatement(
                    "DELETE FROM specific_score_watch_users WHERE group_id = ?");
                 PreparedStatement beatmaps = connection.prepareStatement(
                         "DELETE FROM specific_score_watch_beatmaps WHERE group_id = ?");
                 PreparedStatement watch = connection.prepareStatement(
                         "DELETE FROM specific_score_watches WHERE group_id = ?")) {
                users.setString(1, groupId);
                users.executeUpdate();
                beatmaps.setString(1, groupId);
                beatmaps.executeUpdate();
                watch.setString(1, groupId);
                boolean removed = watch.executeUpdate() > 0;
                connection.commit();
                return removed;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove specific score watch", e);
        }
    }

    public static void updateSpecificScoreWatchCursor(String groupId, long userId, long scoreId) {
        SqliteDatabase.ensureInitialized();
        String sql = """
                UPDATE specific_score_watch_users
                SET last_score_id = ?
                WHERE group_id = ? AND user_id = ?
                """;
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, scoreId);
            statement.setString(2, groupId);
            statement.setLong(3, userId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Specific score watch cursor no longer exists");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update specific score watch cursor", e);
        }
    }

    public static List<SpecificScoreWatchState> findAllSpecificScoreWatches() {
        SqliteDatabase.ensureInitialized();
        Map<String, Set<Long>> usersByGroup = new LinkedHashMap<>();
        Map<String, Set<Long>> beatmapsByGroup = new LinkedHashMap<>();
        Map<String, Map<Long, Long>> cursorsByGroup = new LinkedHashMap<>();
        String groupsSql = "SELECT group_id FROM specific_score_watches ORDER BY group_id";
        String usersSql = """
                SELECT group_id, user_id, last_score_id
                FROM specific_score_watch_users
                ORDER BY group_id, user_id
                """;
        String beatmapsSql = """
                SELECT group_id, beatmap_id
                FROM specific_score_watch_beatmaps
                ORDER BY group_id, beatmap_id
                """;
        try (Connection connection = SqliteDatabase.getConnection()) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(groupsSql)) {
                while (resultSet.next()) {
                    String groupId = resultSet.getString("group_id");
                    usersByGroup.put(groupId, new LinkedHashSet<>());
                    beatmapsByGroup.put(groupId, new LinkedHashSet<>());
                    cursorsByGroup.put(groupId, new LinkedHashMap<>());
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(usersSql)) {
                while (resultSet.next()) {
                    String groupId = resultSet.getString("group_id");
                    long userId = resultSet.getLong("user_id");
                    usersByGroup.computeIfAbsent(groupId, _ -> new LinkedHashSet<>()).add(userId);
                    long lastScoreId = resultSet.getLong("last_score_id");
                    if (!resultSet.wasNull()) {
                        cursorsByGroup.computeIfAbsent(groupId, _ -> new LinkedHashMap<>())
                                .put(userId, lastScoreId);
                    }
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(beatmapsSql)) {
                while (resultSet.next()) {
                    beatmapsByGroup.computeIfAbsent(
                            resultSet.getString("group_id"), _ -> new LinkedHashSet<>()
                    ).add(resultSet.getLong("beatmap_id"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load specific score watches", e);
        }

        List<SpecificScoreWatchState> states = new ArrayList<>();
        usersByGroup.forEach((groupId, userIds) -> {
            Set<Long> beatmapIds = beatmapsByGroup.getOrDefault(groupId, Set.of());
            if (!userIds.isEmpty() && !beatmapIds.isEmpty()) {
                states.add(new SpecificScoreWatchState(
                        groupId, userIds, beatmapIds, cursorsByGroup.getOrDefault(groupId, Map.of())
                ));
            } else {
                LOG.warn("Ignoring incomplete persisted /wx watch for group {}", groupId);
            }
        });
        return List.copyOf(states);
    }

    public static int countBoundUser() {
        SqliteDatabase.ensureInitialized();

        String sql = "SELECT COUNT(*) AS count FROM user_bindings";
        try (Connection connection = SqliteDatabase.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                if (resultSet.next()) {
                    return resultSet.getInt("count");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query binding", e);
        }

        return 0;
    }

    public static int countGroups() {
        SqliteDatabase.ensureInitialized();

        String sql = "SELECT COUNT(DISTINCT group_id) AS count FROM group_members";
        try (Connection connection = SqliteDatabase.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                if (resultSet.next()) {
                    return resultSet.getInt("count");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query group", e);
        }

        return 0;
    }

    public static String executeQueryOrEdit(String sql) {
        SqliteDatabase.ensureInitialized();

        try (Connection c = SqliteDatabase.getConnection();
             Statement stmt = c.createStatement()) {

            // This method should be invoked only in debug context.
            //noinspection SqlSourceToSinkFlow
            boolean isResultSet = stmt.execute(sql);

            if (isResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    StringBuilder sb = new StringBuilder("```\n");

                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    for (int i = 1; i <= columnCount; i++) {
                        sb.append(metaData.getColumnName(i)).append(i == columnCount ? "\n" : " | ");
                    }

                    int rowCount = 0;
                    while (rs.next()) {
                        rowCount++;
                        for (int i = 1; i <= columnCount; i++) {
                            sb.append(rs.getString(i)).append(i == columnCount ? "\n" : " | ");
                        }
                    }

                    sb.append("\n```\n").append("> Rows returned: ").append(rowCount);
                    return sb.toString();
                }
            } else {
                int updateCount = stmt.getUpdateCount();
                return "> Edit executed successfully. Rows affected: " + updateCount;
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute SQL: " + sql, e);
        }
    }

    public static List<Long> findAllUsers() {
        SqliteDatabase.ensureInitialized();

        List<Long> result = new LinkedList<>();

        var queries = new String[]{
                "SELECT self AS id FROM user_follows",
                "SELECT followed AS id FROM user_follows",
                "SELECT osu_uid AS id FROM user_bindings",
                "SELECT uid AS id FROM user_info"
        };

        for (String sql : queries) {
            try (Connection connection = SqliteDatabase.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    result.add(id);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to query binding", e);
            }
        }

        return result.stream().distinct().toList();
    }

    public static void createTablesIfNeeded(Connection connection) throws SQLException {
        String bindingSql = """
                CREATE TABLE IF NOT EXISTS user_bindings (
                    open_id TEXT NOT NULL,
                    osu_uid BIGINT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(open_id)
                )
                """;
        String groupMemberSql = """
                CREATE TABLE IF NOT EXISTS group_members (
                    group_id TEXT NOT NULL,
                    open_id TEXT NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
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
        String discordBridgeSql = """
                CREATE TABLE IF NOT EXISTS discord_bridges (
                    group_id TEXT NOT NULL PRIMARY KEY,
                    guild_id TEXT NOT NULL,
                    channel_id TEXT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """;
        String discordBridgeTargetIndexSql = """
                CREATE INDEX IF NOT EXISTS idx_discord_bridges_target
                ON discord_bridges(guild_id, channel_id)
                """;
        String specificScoreWatchSql = """
                CREATE TABLE IF NOT EXISTS specific_score_watches (
                    group_id TEXT NOT NULL PRIMARY KEY,
                    updated_at BIGINT NOT NULL
                )
                """;
        String specificScoreWatchUserSql = """
                CREATE TABLE IF NOT EXISTS specific_score_watch_users (
                    group_id TEXT NOT NULL,
                    user_id BIGINT NOT NULL,
                    last_score_id BIGINT,
                    PRIMARY KEY(group_id, user_id)
                )
                """;
        String specificScoreWatchBeatmapSql = """
                CREATE TABLE IF NOT EXISTS specific_score_watch_beatmaps (
                    group_id TEXT NOT NULL,
                    beatmap_id BIGINT NOT NULL,
                    PRIMARY KEY(group_id, beatmap_id)
                )
                """;
        try (Statement statement = connection.createStatement()) {
            statement.execute(bindingSql);
            statement.execute(groupMemberSql);
            statement.execute(tokenStoreSql);
            statement.execute(followSql);
            statement.execute(followIndexSql);
            statement.execute(userInfoSql);
            statement.execute(discordBridgeSql);
            statement.execute(discordBridgeTargetIndexSql);
            statement.execute(specificScoreWatchSql);
            statement.execute(specificScoreWatchUserSql);
            statement.execute(specificScoreWatchBeatmapSql);
        }
    }
}


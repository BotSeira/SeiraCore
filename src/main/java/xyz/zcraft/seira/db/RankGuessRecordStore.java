package xyz.zcraft.seira.db;

import xyz.zcraft.seira.rankguess.data.FinishedRound;
import xyz.zcraft.seira.rankguess.data.Standing;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static xyz.zcraft.seira.rankguess.RankGuessGameService.MIN_GAMES_TO_RANK;

public class RankGuessRecordStore {
    /**
     * Returns false when this round has already been saved. All rows are committed together.
     */
    public static boolean save(FinishedRound finished) {
        validate(finished);
        String gameSql = """
                INSERT INTO rank_guess_games (
                    round_id, group_id, source_mode, target_user_id, target_score_id, actual_rank,
                    started_at, ended_at, participant_count, scoring_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(round_id) DO NOTHING
                """;
        String resultSql = """
                INSERT INTO rank_guess_results (
                    round_id, user_id, guessed_rank, placement, raw_score, multiplier, final_score
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = SqliteDatabase.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String roundId = finished.id().toString();
                try (PreparedStatement statement = connection.prepareStatement(gameSql)) {
                    statement.setString(1, roundId);
                    statement.setString(2, finished.groupId());
                    statement.setString(3, finished.fromGroup() ? "group" : "random");
                    statement.setLong(4, finished.round().userId());
                    statement.setLong(5, finished.round().scoreId());
                    statement.setLong(6, finished.round().actualRank());
                    statement.setLong(7, finished.startedAt().toEpochMilli());
                    statement.setLong(8, finished.endedAt().toEpochMilli());
                    statement.setInt(9, finished.standings().size());
                    statement.setInt(10, finished.scoringVersion());
                    if (statement.executeUpdate() == 0) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(resultSql)) {
                    for (int i = 0; i < finished.standings().size(); i++) {
                        Standing result = finished.standings().get(i);
                        statement.setString(1, roundId);
                        statement.setString(2, result.senderUserId());
                        statement.setLong(3, result.guess());
                        statement.setInt(4, i + 1);
                        statement.setDouble(5, result.pointsRaw());
                        statement.setDouble(6, result.multiplier());
                        statement.setDouble(7, result.points());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new RecordSaveException("Failed to save rank guess round " + finished.id(), e);
        }
    }

    public static long getPickedTimes(Long osuUid, String groupId) {
        String sql = """
                SELECT COUNT(*)
                FROM rank_guess_games g
                WHERE g.target_user_id = ?
                """;
        if (groupId != null) sql += " AND g.group_id = ?";
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, osuUid);
            if (groupId != null) statement.setString(2, groupId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query picked times", e);
        }
    }

    public static long getGroupGameCount(String groupId, Integer scoringVersion) {
        requireText(groupId, "groupId");
        if (scoringVersion != null && scoringVersion < 1) {
            throw new IllegalArgumentException("scoringVersion must be positive");
        }

        String sql = """
                SELECT COUNT(*)
                FROM rank_guess_games g
                WHERE g.group_id = ?
                """;

        if (scoringVersion != null) sql += " AND g.scoring_version = ?";
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, groupId);
            if (scoringVersion != null) statement.setInt(2, scoringVersion);

            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query rank guess statistics", e);
        }
    }

    public static boolean canBeRanked(String userId, String groupId) {
        String sql = """
                SELECT COUNT(*) AS participation
                FROM rank_guess_results r
                JOIN rank_guess_games g ON g.round_id = r.round_id
                WHERE r.user_id = ?
                """;
        if (groupId != null) sql += " AND g.group_id = ?";
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            if (groupId != null) statement.setString(2, groupId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong("participation") >= MIN_GAMES_TO_RANK;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query rank guess statistics", e);
        }
    }

    public static Statistics.Personal getPersonalStatistics(String userId, String groupId, Integer scoringVersion, Integer minParticipants) {
        return getPersonalStatistics(userId, groupId, scoringVersion, null, minParticipants);
    }

    public static Statistics.Personal getRecentPersonalStatistics(
            String userId, String groupId, Integer scoringVersion, int gameLimit, Integer minParticipants
    ) {
        if (gameLimit < 1) {
            throw new IllegalArgumentException("gameLimit must be positive");
        }
        if (minParticipants != null && minParticipants < 1) {
            throw new IllegalArgumentException("minParticipants must be positive");
        }
        return getPersonalStatistics(userId, groupId, scoringVersion, gameLimit, minParticipants);
    }

    private static Statistics.Personal getPersonalStatistics(
            String userId, String groupId, Integer scoringVersion, Integer gameLimit, Integer minParticipants
    ) {
        requireText(userId, "userId");
        if (groupId != null) requireText(groupId, "groupId");
        if (scoringVersion != null && scoringVersion < 1) {
            throw new IllegalArgumentException("scoringVersion must be positive");
        }
        String sql = """
                SELECT COUNT(*) AS participation,
                       COALESCE(SUM(CASE WHEN recent.placement = 1 THEN 1 ELSE 0 END), 0) AS wins,
                       COALESCE(SUM(CASE WHEN recent.placement <= (recent.participant_count + 4) / 5 THEN 1 ELSE 0 END), 0) AS top_twenty,
                       COALESCE(SUM(recent.final_score), 0) AS total_score,
                       COALESCE(AVG(recent.final_score), 0) AS average_score,
                       COALESCE(MAX(recent.final_score), 0) AS highest_score,
                       COALESCE(AVG(recent.placement), 0) AS average_placement
                FROM (
                    SELECT r.placement, r.final_score, g.participant_count
                    FROM rank_guess_results r
                    JOIN rank_guess_games g ON g.round_id = r.round_id
                    WHERE r.user_id = ?
                """;
        if (minParticipants != null) sql += " AND g.participant_count >= ?";
        if (groupId != null) sql += " AND g.group_id = ?";
        if (scoringVersion != null) sql += " AND g.scoring_version = ?";
        if (gameLimit != null) sql += " ORDER BY g.ended_at DESC, g.round_id DESC LIMIT ?";
        sql += ") recent";
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            int index = 2;
            if (minParticipants != null) statement.setInt(index++, minParticipants);
            if (groupId != null) statement.setString(index++, groupId);
            if (scoringVersion != null) statement.setInt(index++, scoringVersion);
            if (gameLimit != null) statement.setInt(index, gameLimit);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new Statistics.Personal(
                        result.getLong("participation"), result.getLong("wins"),
                        result.getLong("top_twenty"),
                        result.getDouble("total_score"), result.getDouble("average_score"),
                        result.getDouble("highest_score"), result.getDouble("average_placement")
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query rank guess statistics", e);
        }
    }

    public record RankData(
            Statistics.Personal all,
            Statistics.Personal recent
    ) {}

    public static Map<String, RankData> getGroupRankData(
            String groupId,
            Integer scoringVersion,
            int recentGameLimit,
            Integer minParticipants
    ) {
        if (groupId != null) {
            requireText(groupId, "groupId");
        }

        if (scoringVersion != null && scoringVersion < 1) {
            throw new IllegalArgumentException("scoringVersion must be positive");
        }

        if (recentGameLimit <= 0) {
            throw new IllegalArgumentException("recentGameLimit must be positive");
        }

        String sql = """
            WITH ranked AS (
                SELECT
                    r.user_id,
                    r.placement,
                    r.final_score,
                    g.participant_count,
                    ROW_NUMBER() OVER (
                        PARTITION BY r.user_id
                        ORDER BY g.ended_at DESC, g.round_id DESC
                    ) AS rn
                FROM rank_guess_results r
                JOIN rank_guess_games g
                    ON g.round_id = r.round_id
                WHERE 1 = 1
            """;

        if (groupId != null) {
            sql += " AND g.group_id = ?";
        }

        if (minParticipants != null) {
            sql += " AND g.participant_count >= ?";
        }

        if (scoringVersion != null) {
            sql += " AND g.scoring_version = ?";
        }

        sql += """
            ),
            all_stats AS (
                SELECT
                    user_id,
                    COUNT(*) AS participation,
                    SUM(CASE WHEN placement = 1 THEN 1 ELSE 0 END) AS wins,
                    SUM(
                        CASE
                            WHEN placement <= (participant_count + 4) / 5
                            THEN 1
                            ELSE 0
                        END
                    ) AS top_twenty,
                    SUM(final_score) AS total_score,
                    AVG(final_score) AS average_score,
                    MAX(final_score) AS highest_score,
                    AVG(placement) AS average_placement
                FROM ranked
                GROUP BY user_id
            ),
            recent_stats AS (
                SELECT
                    user_id,
                    COUNT(*) AS participation,
                    SUM(CASE WHEN placement = 1 THEN 1 ELSE 0 END) AS wins,
                    SUM(
                        CASE
                            WHEN placement <= (participant_count + 4) / 5
                            THEN 1
                            ELSE 0
                        END
                    ) AS top_twenty,
                    SUM(final_score) AS total_score,
                    AVG(final_score) AS average_score,
                    MAX(final_score) AS highest_score,
                    AVG(placement) AS average_placement
                FROM ranked
                WHERE rn <= ?
                GROUP BY user_id
            )
            SELECT
                a.user_id,

                a.participation AS all_participation,
                a.wins AS all_wins,
                a.top_twenty AS all_top_twenty,
                a.total_score AS all_total_score,
                a.average_score AS all_average_score,
                a.highest_score AS all_highest_score,
                a.average_placement AS all_average_placement,

                COALESCE(r.participation, 0) AS recent_participation,
                COALESCE(r.wins, 0) AS recent_wins,
                COALESCE(r.top_twenty, 0) AS recent_top_twenty,
                COALESCE(r.total_score, 0) AS recent_total_score,
                COALESCE(r.average_score, 0) AS recent_average_score,
                COALESCE(r.highest_score, 0) AS recent_highest_score,
                COALESCE(r.average_placement, 0) AS recent_average_placement

            FROM all_stats a
            LEFT JOIN recent_stats r
                ON r.user_id = a.user_id
            """;

        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            int index = 1;

            if (groupId != null) {
                statement.setString(index++, groupId);
            }

            if (minParticipants != null) {
                statement.setInt(index++, minParticipants);
            }

            if (scoringVersion != null) {
                statement.setInt(index++, scoringVersion);
            }

            statement.setInt(index, recentGameLimit);

            Map<String, RankData> resultMap = new HashMap<>();

            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Statistics.Personal all = new Statistics.Personal(
                            result.getLong("all_participation"),
                            result.getLong("all_wins"),
                            result.getLong("all_top_twenty"),
                            result.getDouble("all_total_score"),
                            result.getDouble("all_average_score"),
                            result.getDouble("all_highest_score"),
                            result.getDouble("all_average_placement")
                    );

                    Statistics.Personal recent = new Statistics.Personal(
                            result.getLong("recent_participation"),
                            result.getLong("recent_wins"),
                            result.getLong("recent_top_twenty"),
                            result.getDouble("recent_total_score"),
                            result.getDouble("recent_average_score"),
                            result.getDouble("recent_highest_score"),
                            result.getDouble("recent_average_placement")
                    );

                    resultMap.put(
                            result.getString("user_id"),
                            new RankData(all, recent)
                    );
                }
            }

            return resultMap;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to query rank guess data",
                    e
            );
        }
    }

    private static void validate(FinishedRound finished) {
        Objects.requireNonNull(finished, "finished");
        Objects.requireNonNull(finished.id(), "id");
        Objects.requireNonNull(finished.round(), "round");
        Objects.requireNonNull(finished.startedAt(), "startedAt");
        Objects.requireNonNull(finished.endedAt(), "endedAt");
        requireText(finished.groupId(), "groupId");
        if (finished.endedAt().isBefore(finished.startedAt()) || finished.scoringVersion() < 1) {
            throw new IllegalArgumentException("Invalid round timestamps or scoring version");
        }
        for (Standing result : finished.standings()) {
            requireText(result.senderUserId(), "userId");
            if (result.guess() <= 0 || !Double.isFinite(result.pointsRaw()) || result.pointsRaw() < 0
                    || !Double.isFinite(result.multiplier()) || result.multiplier() < 0
                    || !Double.isFinite(result.points()) || result.points() < 0) {
                throw new IllegalArgumentException("Invalid rank guess result");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    public static void createTablesIfNeeded(Connection connection) throws SQLException {
        String rgGameSql = """
                CREATE TABLE IF NOT EXISTS rank_guess_games (
                     round_id          TEXT PRIMARY KEY,
                     group_id          TEXT NOT NULL,
                     source_mode       TEXT NOT NULL
                                       CHECK (source_mode IN ('random', 'group')),
                
                     target_user_id    INTEGER NOT NULL,
                     target_score_id   INTEGER NOT NULL,
                     actual_rank       INTEGER NOT NULL CHECK (actual_rank > 0),
                
                     started_at        INTEGER NOT NULL,
                     ended_at          INTEGER NOT NULL,
                     participant_count INTEGER NOT NULL CHECK (participant_count >= 0),
                     scoring_version   INTEGER NOT NULL DEFAULT 1
                 );
                """;
        String rgResultSql = """
                CREATE TABLE IF NOT EXISTS rank_guess_results (
                     round_id          TEXT NOT NULL,
                     user_id           TEXT NOT NULL,
                
                     guessed_rank      INTEGER NOT NULL CHECK (guessed_rank > 0),
                     placement         INTEGER NOT NULL CHECK (placement > 0),
                     raw_score         REAL NOT NULL,
                     multiplier        REAL NOT NULL,
                     final_score       REAL NOT NULL,
                
                     PRIMARY KEY (round_id, user_id),
                     FOREIGN KEY (round_id) REFERENCES rank_guess_games(round_id)
                 );
                """;
        String rgResultIndex = """
                CREATE INDEX IF NOT EXISTS idx_rg_results_user
                    ON rank_guess_results(user_id, round_id);
                """;
        String rgGameIndex = """
                CREATE INDEX IF NOT EXISTS idx_rg_games_group_time
                    ON rank_guess_games(group_id, ended_at);
                """;
        try (Statement statement = connection.createStatement()) {
            statement.execute(rgGameSql);
            statement.execute(rgResultSql);
            statement.execute(rgResultIndex);
            statement.execute(rgGameIndex);
        }
    }

    public static RankGuessed getAverageRankGuessed(Long osuUid, String groupId) {
        String sql = """
            WITH ranked AS (
                SELECT
                    g.round_id,
                    g.target_user_id,
                    r.guessed_rank,

                    ROW_NUMBER() OVER (
                        PARTITION BY g.round_id
                        ORDER BY r.guessed_rank ASC, r.user_id ASC
                    ) AS low_rank,

                    ROW_NUMBER() OVER (
                        PARTITION BY g.round_id
                        ORDER BY r.guessed_rank DESC, r.user_id DESC
                    ) AS high_rank

                FROM rank_guess_games g
                JOIN rank_guess_results r
                    ON r.round_id = g.round_id

                WHERE g.target_user_id = ?
            """;

        if (groupId != null) {
            sql += " AND g.group_id = ?";
        }

        sql += """
            )
            SELECT
                AVG(guessed_rank) AS avg_guessed_rank,
                POW(10, AVG(LOG10(guessed_rank))) AS log_avg_guessed_rank
            FROM ranked
            WHERE low_rank > 1
              AND high_rank > 1
            """;

        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, osuUid);

            if (groupId != null) {
                statement.setString(2, groupId);
            }

            try (ResultSet result = statement.executeQuery()) {
                result.next();

                return new RankGuessed(
                        result.getDouble("avg_guessed_rank"),
                        result.getDouble("log_avg_guessed_rank")
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query average rank guessed", e);
        }
    }

    public static long getTotalGamesCount(String groupId) {
        String sql = """
                SELECT COUNT(*)
                FROM rank_guess_games g
                """;
        if (groupId != null) sql += " AND g.group_id = ?";
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (groupId != null) statement.setString(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query picked times", e);
        }
    }

    public static Map<Long, Integer> getGamesSincePicked(String groupId) {
        String sql = """
                WITH group_games AS (
                    SELECT
                        target_user_id,
                        ROW_NUMBER() OVER (
                            ORDER BY ended_at ASC, round_id ASC
                        ) AS game_no
                    FROM rank_guess_games
                    WHERE group_id = ?
                    AND source_mode = 'group'
                ),
                latest_pick AS (
                    SELECT
                        target_user_id,
                        MAX(game_no) AS last_game_no
                    FROM group_games
                    GROUP BY target_user_id
                ),
                total AS (
                    SELECT COALESCE(MAX(game_no), 0) AS current_game_no
                    FROM group_games
                )
                SELECT
                    latest_pick.target_user_id,
                    total.current_game_no - latest_pick.last_game_no AS games_since_picked
                FROM latest_pick
                CROSS JOIN total;
                """;

        Map<Long, Integer> gamesSincePicked = new HashMap<>();
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (groupId != null) statement.setString(1, groupId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    gamesSincePicked.put(
                            result.getLong("target_user_id"),
                            result.getInt("games_since_picked")
                    );
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query picked times", e);
        }

        return gamesSincePicked;
    }

    public static class Statistics {
        public record Personal(
                long participation, long wins, long topTwentyCount,
                double totalScore, double averageScore, double highestScore, double averagePlacement
        ) {
            public double winRate() {
                return participation == 0 ? 0 : wins / (double) participation;
            }

            public double topTwentyRate() {
                return participation == 0 ? 0
                        : topTwentyCount / (double) participation;
            }
        }
    }

    public static final class RecordSaveException extends RuntimeException {
        public RecordSaveException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record RankGuessed(double average, double logAverage) {
    }
}

package xyz.zcraft.seira.db;

import xyz.zcraft.seira.rankguess.data.FinishedRound;
import xyz.zcraft.seira.rankguess.data.Standing;

import java.sql.*;
import java.util.Objects;

import static xyz.zcraft.seira.rankguess.RankGuessGameService.MIN_GAMES_TO_RANK;

public class RankGuessRecordStore {
    public static final int TOP_TWENTY_MIN_PARTICIPANTS = 1;

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

    public static Statistics.Personal getPersonalStatistics(String userId, String groupId, Integer scoringVersion) {
        return getPersonalStatistics(userId, groupId, scoringVersion, null);
    }

    public static Statistics.Personal getRecentPersonalStatistics(
            String userId, String groupId, Integer scoringVersion, int gameLimit
    ) {
        if (gameLimit < 1) {
            throw new IllegalArgumentException("gameLimit must be positive");
        }
        return getPersonalStatistics(userId, groupId, scoringVersion, gameLimit);
    }

    private static Statistics.Personal getPersonalStatistics(
            String userId, String groupId, Integer scoringVersion, Integer gameLimit
    ) {
        requireText(userId, "userId");
        if (groupId != null) requireText(groupId, "groupId");
        if (scoringVersion != null && scoringVersion < 1) {
            throw new IllegalArgumentException("scoringVersion must be positive");
        }
        String sql = """
                SELECT COUNT(*) AS participation,
                       COALESCE(SUM(CASE WHEN recent.placement = 1 THEN 1 ELSE 0 END), 0) AS wins,
                       COALESCE(SUM(CASE WHEN recent.participant_count >= ?
                           AND recent.placement <= (recent.participant_count + 4) / 5 THEN 1 ELSE 0 END), 0) AS top_twenty,
                       COALESCE(SUM(CASE WHEN recent.participant_count >= ? THEN 1 ELSE 0 END), 0) AS top_twenty_eligible,
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
        if (groupId != null) sql += " AND g.group_id = ?";
        if (scoringVersion != null) sql += " AND g.scoring_version = ?";
        if (gameLimit != null) sql += " ORDER BY g.ended_at DESC, g.round_id DESC LIMIT ?";
        sql += ") recent";
        try (Connection connection = SqliteDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, TOP_TWENTY_MIN_PARTICIPANTS);
            statement.setInt(2, TOP_TWENTY_MIN_PARTICIPANTS);
            statement.setString(3, userId);
            int index = 4;
            if (groupId != null) statement.setString(index++, groupId);
            if (scoringVersion != null) statement.setInt(index++, scoringVersion);
            if (gameLimit != null) statement.setInt(index, gameLimit);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new Statistics.Personal(
                        result.getLong("participation"), result.getLong("wins"),
                        result.getLong("top_twenty"), result.getLong("top_twenty_eligible"),
                        result.getDouble("total_score"), result.getDouble("average_score"),
                        result.getDouble("highest_score"), result.getDouble("average_placement")
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query rank guess statistics", e);
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

    public static class Statistics {
        public record Personal(
                long participation, long wins, long topTwentyCount, long topTwentyEligibleParticipation,
                double totalScore, double averageScore, double highestScore, double averagePlacement
        ) {
            public double winRate() {
                return participation == 0 ? 0 : wins / (double) participation;
            }

            public double topTwentyRate() {
                return topTwentyEligibleParticipation == 0 ? 0
                        : topTwentyCount / (double) topTwentyEligibleParticipation;
            }
        }
    }

    public static final class RecordSaveException extends RuntimeException {
        public RecordSaveException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

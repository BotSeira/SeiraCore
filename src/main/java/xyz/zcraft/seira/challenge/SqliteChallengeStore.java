package xyz.zcraft.seira.challenge;

import com.google.gson.Gson;
import xyz.zcraft.seira.challenge.ChallengeModels.Draft;
import xyz.zcraft.seira.challenge.ChallengeModels.Round;
import xyz.zcraft.seira.db.SqliteDatabase;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class SqliteChallengeStore implements ChallengeStore {
    private final Connections connections;
    private final Gson gson = new Gson();
    public SqliteChallengeStore() {
        this(SqliteDatabase::getConnection);
    }

    public SqliteChallengeStore(Connections connections) {
        this.connections = connections;
        try (var connection = connections.open(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS group_challenges (
                        id TEXT PRIMARY KEY, group_id TEXT NOT NULL,
                        finished INTEGER NOT NULL, notified INTEGER NOT NULL, data TEXT NOT NULL)
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS group_challenges_group ON group_challenges(group_id)");
            statement.execute("CREATE TABLE IF NOT EXISTS group_challenge_drafts (group_id TEXT PRIMARY KEY, data TEXT NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("创建群挑战存储失败。", e);
        }
    }

    public List<Round> load() {
        // Keep latest results and every unfinished/unannounced round, without loading all past events.
        try (var connection = connections.open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT data FROM group_challenges
                     WHERE finished = 0 OR notified = 0 OR rowid IN
                         (SELECT MAX(rowid) FROM group_challenges GROUP BY group_id)
                     ORDER BY rowid
                     """)) {
            List<Round> rounds = new ArrayList<>();
            while (rows.next()) rounds.add(gson.fromJson(rows.getString(1), Round.class));
            return List.copyOf(rounds);
        } catch (SQLException e) {
            throw new IllegalStateException("读取群挑战失败。", e);
        }
    }

    public void save(Round round) {
        try (var connection = connections.open(); var statement = connection.prepareStatement("""
                INSERT INTO group_challenges(id, group_id, finished, notified, data) VALUES(?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET finished=excluded.finished, notified=excluded.notified, data=excluded.data
                """)) {
            statement.setString(1, round.id());
            statement.setString(2, round.groupId());
            statement.setBoolean(3, round.finished());
            statement.setBoolean(4, round.notified());
            statement.setString(5, gson.toJson(round));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存群挑战失败。", e);
        }
    }

    public List<Draft> loadDrafts() {
        try (var connection = connections.open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT data FROM group_challenge_drafts")) {
            List<Draft> drafts = new ArrayList<>();
            while (rows.next()) drafts.add(gson.fromJson(rows.getString(1), Draft.class));
            return List.copyOf(drafts);
        } catch (SQLException e) {
            throw new IllegalStateException("读取挑战配置失败。", e);
        }
    }

    public void saveDraft(Draft draft) {
        try (var connection = connections.open(); var statement = connection.prepareStatement("""
                INSERT INTO group_challenge_drafts(group_id, data) VALUES(?,?)
                ON CONFLICT(group_id) DO UPDATE SET data=excluded.data
                """)) {
            statement.setString(1, draft.groupId());
            statement.setString(2, gson.toJson(draft));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存挑战配置失败。", e);
        }
    }

    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }
}

package xyz.zcraft.seira.db;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SqliteDatabase {
    private static final Logger LOG = LogManager.getLogger(SqliteDatabase.class);
    private static final Object INIT_LOCK = new Object();

    private static volatile String jdbcUrl;
    private static volatile Path initializedDbPath;

    public static void init(String sqlitePath) {
        synchronized (INIT_LOCK) {
            if (jdbcUrl != null && initializedDbPath != null && Files.exists(initializedDbPath)) {
                return;
            }
            try {
                Path dbPath = Path.of(sqlitePath).toAbsolutePath().normalize();
                Path parent = dbPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                String createdUrl = "jdbc:sqlite:" + dbPath;
                try (Connection connection = openConnection(createdUrl)) {
                    connection.setAutoCommit(false);
                    UserDataStore.createTablesIfNeeded(connection);
                    RankGuessRecordStore.createTablesIfNeeded(connection);
                    connection.commit();
                }
                initializedDbPath = dbPath;
                jdbcUrl = createdUrl;

                LOG.info("SQLite database initialized at {}", dbPath);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize sqlite store: " + sqlitePath, e);
            }
        }
    }

    public static Connection getConnection() throws SQLException {
        ensureInitialized();
        return openConnection(jdbcUrl);
    }

    private static Connection openConnection(String url) throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    public static void ensureInitialized() {
        if (jdbcUrl == null) {
            throw new IllegalStateException("SQLite database is not initialized");
        }
    }

    public static QueryResult queryReadOnly(String sql, int maxRows) {
        SqliteDatabase.ensureInitialized();
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL query must not be blank");
        }
        if (maxRows < 1 || maxRows > 200) {
            throw new IllegalArgumentException("maxRows must be between 1 and 200");
        }

        String statementSql = sql.strip();
        if (statementSql.endsWith(";")) {
            statementSql = statementSql.substring(0, statementSql.length() - 1).stripTrailing();
        }
        if (statementSql.contains(";")) {
            throw new IllegalArgumentException("Multiple SQL statements are not allowed");
        }
        String normalized = statementSql.toLowerCase(Locale.ROOT);
        String keyword = normalized.split("\\s+", 2)[0];
        if (!List.of("select", "with", "pragma", "explain").contains(keyword)) {
            throw new IllegalArgumentException("Only SELECT, WITH, PRAGMA and EXPLAIN queries are allowed");
        }
        if ("pragma".equals(keyword) && !isAllowedReadOnlyPragma(normalized)) {
            throw new IllegalArgumentException("This PRAGMA is not allowed in the read-only console");
        }

        try (Connection connection = SqliteDatabase.getConnection();
             Statement queryOnly = connection.createStatement();
             Statement statement = connection.createStatement()) {
            queryOnly.execute("PRAGMA query_only = ON");
            statement.setMaxRows(maxRows + 1);
            statement.setQueryTimeout(10);
            // SQL is intentionally accepted from the trusted local console. The
            // SQLite connection is query-only and the result size is bounded.
            //noinspection SqlSourceToSinkFlow
            try (ResultSet resultSet = statement.executeQuery(statementSql)) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();
                List<String> columns = new ArrayList<>(columnCount);
                for (int index = 1; index <= columnCount; index++) {
                    columns.add(metadata.getColumnLabel(index));
                }

                List<List<String>> rows = new ArrayList<>();
                boolean truncated = false;
                while (resultSet.next()) {
                    if (rows.size() == maxRows) {
                        truncated = true;
                        break;
                    }
                    List<String> row = new ArrayList<>(columnCount);
                    for (int index = 1; index <= columnCount; index++) {
                        row.add(resultSet.getString(index));
                    }
                    rows.add(List.copyOf(row));
                }
                return new QueryResult(List.copyOf(columns), List.copyOf(rows), truncated);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute read-only SQL query", e);
        }
    }

    private static boolean isAllowedReadOnlyPragma(String normalizedSql) {
        String pragma = normalizedSql.substring("pragma".length()).stripLeading();
        int separator = pragma.indexOf('(');
        String name = (separator >= 0 ? pragma.substring(0, separator) : pragma).strip();
        return List.of(
                "table_info", "table_xinfo", "table_list", "index_list", "index_info",
                "index_xinfo", "foreign_key_list", "database_list", "compile_options"
        ).contains(name);
    }

    public record QueryResult(List<String> columns, List<List<String>> rows, boolean truncated) {
    }
}

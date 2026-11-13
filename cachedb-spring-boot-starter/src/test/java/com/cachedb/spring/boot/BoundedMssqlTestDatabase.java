package com.reactor.cachedb.spring.boot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Owns one disposable database; never changes model or a caller's existing database. */
public final class BoundedMssqlTestDatabase implements AutoCloseable {
    public enum Scope {
        WRITE_FENCE("cachedb_fence_sarg_"),
        SNAPSHOT_OFF("snapshot_off_");

        private final String prefix;

        Scope(String prefix) {
            this.prefix = prefix;
        }
    }

    record ModelFiles(long files, long dataFiles, long logFiles, long dataPages, long logPages) {}

    private final DataSource admin;
    private final String name;
    private boolean createAttempted;

    private BoundedMssqlTestDatabase(DataSource admin, Scope scope) {
        this.admin = Objects.requireNonNull(admin);
        name = Objects.requireNonNull(scope).prefix + UUID.randomUUID().toString().replace("-", "");
    }

    public static BoundedMssqlTestDatabase create(DataSource admin, Scope scope, String collation)
            throws Exception {
        var owned = new BoundedMssqlTestDatabase(admin, scope);
        try {
            try (var connection = admin.getConnection()) {
                requireMaster(connection);
                String sql = preflight(connection, owned.name, collation);
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(15);
                    // A timed-out CREATE may have succeeded. Cleanup must also cover that uncertainty.
                    owned.createAttempted = true;
                    statement.execute(sql);
                }
            }
            return owned;
        } catch (Throwable failure) {
            // An explicit name collision is not permission to drop an existing database.
            if (failure instanceof SQLException sql && sql.getErrorCode() == 1801) {
                owned.createAttempted = false;
            }
            closeAfterFailure(failure, owned);
            throw failure;
        }
    }

    public String name() {
        return name;
    }

    private static String preflight(Connection connection, String database, String collation)
            throws SQLException {
        String query = """
                SELECT CONVERT(nvarchar(4000), SERVERPROPERTY('InstanceDefaultDataPath')),
                       CONVERT(nvarchar(4000), SERVERPROPERTY('InstanceDefaultLogPath')),
                       COUNT_BIG(*),
                       SUM(CASE WHEN type = 0 THEN CONVERT(bigint, 1) ELSE 0 END),
                       SUM(CASE WHEN type = 1 THEN CONVERT(bigint, 1) ELSE 0 END),
                       MAX(CASE WHEN type = 0 THEN CONVERT(bigint, size) END),
                       MAX(CASE WHEN type = 1 THEN CONVERT(bigint, size) END),
                       DB_ID(?)
                FROM sys.master_files
                WHERE database_id = DB_ID(N'model')
                """;
        try (var statement = connection.prepareStatement(query)) {
            statement.setQueryTimeout(10);
            statement.setString(1, database);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Missing disposable SQL metadata");
                if (rows.getObject(8) != null) {
                    throw new IllegalStateException("Disposable SQL name already exists; refusing ownership");
                }
                String sql = createSql(database, rows.getString(1), rows.getString(2),
                        new ModelFiles(rows.getLong(3), rows.getLong(4), rows.getLong(5),
                                rows.getLong(6), rows.getLong(7)), collation);
                if (rows.next()) throw new IllegalStateException("Ambiguous disposable SQL metadata");
                return sql;
            }
        }
    }

    static String createSql(String database, String dataDirectory, String logDirectory,
            ModelFiles model, String collation) {
        requireOwnedName(database);
        String data = directory(dataDirectory);
        String log = directory(logDirectory);
        if (model == null || model.files() != 2 || model.dataFiles() != 1 || model.logFiles() != 1
                || model.dataPages() < 1 || model.dataPages() > 1024
                || model.logPages() < 1 || model.logPages() > 1024) {
            throw new IllegalStateException("Model files do not fit the bounded 8 MiB allocation");
        }
        if (collation != null && !collation.equals("SQL_Latin1_General_CP1_CI_AS")
                && !collation.equals("Latin1_General_100_CI_AS_SC")) {
            throw new IllegalArgumentException("Disposable SQL collation is not allowlisted");
        }
        return "CREATE DATABASE [" + database + "] ON PRIMARY (NAME=N'" + database
                + "', FILENAME=N'" + data + database
                + ".mdf', SIZE=8MB, MAXSIZE=64MB, FILEGROWTH=8MB) LOG ON (NAME=N'"
                + database + "_log', FILENAME=N'" + log + database
                + "_log.ldf', SIZE=8MB, MAXSIZE=32MB, FILEGROWTH=8MB)"
                + (collation == null ? "" : " COLLATE " + collation);
    }

    private static String directory(String path) {
        if (!"/var/opt/mssql/data".equals(path) && !"/var/opt/mssql/data/".equals(path)) {
            throw new IllegalStateException("Disposable SQL files require the dedicated data volume");
        }
        return "/var/opt/mssql/data/";
    }

    private static void requireOwnedName(String name) {
        if (name == null || !name.matches("(?:cachedb_fence_sarg_|snapshot_off_)[0-9a-f]{32}")) {
            throw new IllegalStateException("Disposable SQL name is not an owned UUID");
        }
    }

    private static void requireMaster(Connection connection) throws SQLException {
        if (!"master".equalsIgnoreCase(connection.getCatalog())) {
            throw new IllegalStateException("Explicit disposable SQL master connection required");
        }
    }

    @Override
    public void close() throws Exception {
        if (!createAttempted) return;
        requireOwnedName(name);
        try (var connection = admin.getConnection()) {
            requireMaster(connection);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(15);
                statement.execute("IF DB_ID(N'" + name + "') IS NOT NULL BEGIN ALTER DATABASE [" + name
                        + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [" + name + "]; END");
            }
        }
        createAttempted = false;
    }

    static void closeAfterFailure(Throwable failure, AutoCloseable... resources) {
        for (var resource : resources) {
            if (resource == null) continue;
            try {
                resource.close();
            } catch (Throwable cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
        }
    }

    public static void closeResources(AutoCloseable... resources) throws Exception {
        Throwable failure = null;
        for (var resource : resources) {
            if (resource == null) continue;
            try {
                resource.close();
            } catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new AssertionError(failure);
    }
}

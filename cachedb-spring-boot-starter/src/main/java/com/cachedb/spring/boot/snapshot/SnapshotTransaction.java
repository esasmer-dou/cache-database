package com.reactor.cachedb.spring.boot.snapshot;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Transaction-wide consistency without changing database configuration or leaking pool state. */
final class SnapshotTransaction implements AutoCloseable {
    private final Connection connection;
    private final boolean autoCommit;
    private final boolean readOnly;
    private final int isolation;

    private SnapshotTransaction(Connection connection) throws SQLException {
        this.connection = connection;
        autoCommit = connection.getAutoCommit();
        readOnly = connection.isReadOnly();
        isolation = connection.getTransactionIsolation();
    }

    static SnapshotTransaction begin(Connection connection) throws SQLException {
        SnapshotTransaction scope = new SnapshotTransaction(connection);
        try {
            String product = connection.getMetaData().getDatabaseProductName();
            switch (product) {
                case "PostgreSQL", "H2" -> {
                    connection.setReadOnly(true);
                    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    connection.setAutoCommit(false);
                }
                case "Microsoft SQL Server" -> {
                    // This is an operational prerequisite, never an automatic ALTER DATABASE.
                    try (Statement statement = connection.createStatement()) {
                        statement.setQueryTimeout(10);
                        try (var result =
                                statement.executeQuery(
                                        "SELECT snapshot_isolation_state FROM sys.databases WHERE"
                                            + " name = DB_NAME()")) {
                            if (!result.next() || result.getInt(1) != 1)
                                throw new SQLException(
                                        "SnapshotPlan requires ALLOW_SNAPSHOT_ISOLATION ON for SQL"
                                            + " Server. Ask the database administrator to enable it"
                                            + " before warming.",
                                        "25000");
                        }
                    }
                    // Microsoft JDBC TRANSACTION_SNAPSHOT; no runtime driver dependency here.
                    connection.setTransactionIsolation(4096);
                    connection.setReadOnly(true);
                    connection.setAutoCommit(false);
                }
                case "Oracle" -> {
                    connection.setAutoCommit(false);
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    try (Statement statement = connection.createStatement()) {
                        statement.setQueryTimeout(10);
                        statement.execute("SET TRANSACTION READ ONLY");
                    }
                }
                default ->
                        throw new IllegalArgumentException(
                                "Unsupported SnapshotPlan database product: " + product);
            }
            return scope;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                scope.close();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public void close() throws SQLException {
        SQLException failure = null;
        try {
            if (!connection.getAutoCommit()) connection.rollback();
        } catch (SQLException error) {
            // Never switch auto-commit back on after an unsuccessful rollback.
            throw error;
        }
        try {
            if (connection.getTransactionIsolation() != isolation)
                connection.setTransactionIsolation(isolation);
            if (connection.isReadOnly() != readOnly) connection.setReadOnly(readOnly);
            if (connection.getAutoCommit() != autoCommit) connection.setAutoCommit(autoCommit);
        } catch (SQLException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }
}

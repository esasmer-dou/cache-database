package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.queue.StaleWriteRejectedException;
import com.reactor.cachedb.core.registry.EntityRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public final class VersionGuardedWriteSupport {

    private VersionGuardedWriteSupport() {
    }

    public static void verifySingleOutcome(
            Connection connection,
            EntityRegistry entityRegistry,
            QueuedWriteOperation operation,
            int affectedRows
    ) throws SQLException {
        verifySingleOutcome(connection, entityRegistry, operation, affectedRows, 30);
    }

    public static void verifySingleOutcome(
            Connection connection,
            EntityRegistry entityRegistry,
            QueuedWriteOperation operation,
            int affectedRows,
            int queryTimeoutSeconds
    ) throws SQLException {
        if (affectedRows > 0 || affectedRows == Statement.SUCCESS_NO_INFO) {
            return;
        }
        if (affectedRows == Statement.EXECUTE_FAILED) {
            throw new SQLException("JDBC batch execution failed for entity=" + operation.entityName() + ", id=" + operation.id());
        }
        Long currentVersion = currentVersion(connection, entityRegistry, operation, queryTimeoutSeconds);
        if (operation.type() == OperationType.DELETE && currentVersion == null) {
            return;
        }
        if (currentVersion != null && currentVersion == operation.version()) {
            return;
        }
        throw new StaleWriteRejectedException(operation, currentVersion);
    }

    public static void verifyBatchOutcome(
            Connection connection,
            EntityRegistry entityRegistry,
            List<QueuedWriteOperation> operations,
            int[] affectedRows
    ) throws SQLException {
        verifyBatchOutcome(connection, entityRegistry, operations, affectedRows, 30);
    }

    public static void verifyBatchOutcome(
            Connection connection,
            EntityRegistry entityRegistry,
            List<QueuedWriteOperation> operations,
            int[] affectedRows,
            int queryTimeoutSeconds
    ) throws SQLException {
        if (affectedRows == null || affectedRows.length != operations.size()) {
            throw new SQLException(
                    "JDBC batch returned an unexpected result count: expected=" + operations.size()
                            + ", actual=" + (affectedRows == null ? "null" : affectedRows.length)
            );
        }
        for (int index = 0; index < operations.size(); index++) {
            verifySingleOutcome(connection, entityRegistry, operations.get(index), affectedRows[index], queryTimeoutSeconds);
        }
    }

    public static void verifyAggregateOutcome(
            Connection connection,
            EntityRegistry entityRegistry,
            List<QueuedWriteOperation> operations,
            int affectedRows
    ) throws SQLException {
        verifyAggregateOutcome(connection, entityRegistry, operations, affectedRows, 30);
    }

    public static void verifyAggregateOutcome(
            Connection connection,
            EntityRegistry entityRegistry,
            List<QueuedWriteOperation> operations,
            int affectedRows,
            int queryTimeoutSeconds
    ) throws SQLException {
        if (affectedRows == operations.size() || affectedRows == Statement.SUCCESS_NO_INFO) {
            return;
        }
        if (affectedRows < 0 || affectedRows > operations.size()) {
            throw new SQLException(
                    "Version-guarded statement returned an invalid affected-row count: expected at most="
                            + operations.size() + ", actual=" + affectedRows
            );
        }
        for (QueuedWriteOperation operation : operations) {
            Long currentVersion = currentVersion(connection, entityRegistry, operation, queryTimeoutSeconds);
            if (operation.type() == OperationType.DELETE && currentVersion == null) {
                continue;
            }
            if (currentVersion != null && currentVersion == operation.version()) {
                continue;
            }
            throw new StaleWriteRejectedException(operation, currentVersion);
        }
    }

    public static Long currentVersion(
            Connection connection,
            EntityRegistry entityRegistry,
            QueuedWriteOperation operation
    ) throws SQLException {
        return currentVersion(connection, entityRegistry, operation, 30);
    }

    public static Long currentVersion(
            Connection connection,
            EntityRegistry entityRegistry,
            QueuedWriteOperation operation,
            int queryTimeoutSeconds
    ) throws SQLException {
        String sql = "SELECT " + operation.versionColumn() + " FROM " + operation.tableName()
                + " WHERE " + operation.idColumn() + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(Math.max(1, queryTimeoutSeconds));
            statement.setObject(1, JdbcWriteBehindSupport.convertValue(
                    operation.id(),
                    JdbcWriteBehindSupport.columnType(entityRegistry, operation, operation.idColumn())
            ));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                long version = resultSet.getLong(1);
                return resultSet.wasNull() ? null : version;
            }
        }
    }
}

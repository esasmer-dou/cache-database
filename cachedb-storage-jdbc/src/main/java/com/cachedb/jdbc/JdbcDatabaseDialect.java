package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.queue.QueuedWriteOperation;

import java.util.List;
import java.util.Map;

public interface JdbcDatabaseDialect {
    String name();

    default int maxColumnsInTable() { return 1_024; }

    default int resolveMaxColumnsInTable(java.sql.DatabaseMetaData metaData) throws java.sql.SQLException {
        return JdbcColumnLimits.resolve(metaData, maxColumnsInTable());
    }

    int maxParametersPerStatement();

    default int upsertParameterCount(QueuedWriteOperation operation) {
        return operation.columns().size();
    }

    default boolean supportsMultiRowUpsert() {
        return true;
    }

    default boolean supportsBulkCopy() {
        return false;
    }

    String deleteSql(QueuedWriteOperation operation);

    String deleteMultiRowSql(QueuedWriteOperation operation, int rowCount);

    String upsertSql(QueuedWriteOperation operation, List<Map.Entry<String, String>> entries);

    String upsertMultiRowSql(QueuedWriteOperation operation, List<Map.Entry<String, String>> entries, int rowCount);

    String sqlCastType(String javaTypeName);

    default int safeStatementRowLimit(int requestedRowLimit, int parametersPerRow) {
        int safeParametersPerRow = Math.max(1, parametersPerRow);
        if (safeParametersPerRow > maxParametersPerStatement()) {
            throw new IllegalArgumentException("Single row parameter count " + safeParametersPerRow
                    + " exceeds statement limit " + maxParametersPerStatement() + " for dialect " + name());
        }
        int maximumRows = Math.max(1, maxParametersPerStatement() / safeParametersPerRow);
        if (requestedRowLimit <= 0) {
            return maximumRows;
        }
        return Math.max(1, Math.min(requestedRowLimit, maximumRows));
    }
}

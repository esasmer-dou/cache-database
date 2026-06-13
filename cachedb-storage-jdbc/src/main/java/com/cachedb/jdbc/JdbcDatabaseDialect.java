package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.queue.QueuedWriteOperation;

import java.util.List;
import java.util.Map;

public interface JdbcDatabaseDialect {
    String name();

    int maxParametersPerStatement();

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
        int maximumRows = Math.max(1, maxParametersPerStatement() / safeParametersPerRow);
        if (requestedRowLimit <= 0) {
            return maximumRows;
        }
        return Math.max(1, Math.min(requestedRowLimit, maximumRows));
    }
}

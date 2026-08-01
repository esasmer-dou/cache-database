package com.reactor.cachedb.core.queue;

import com.reactor.cachedb.core.model.OperationType;

import java.time.Instant;
import java.util.Map;

public record QueuedWriteOperation(
        OperationType type,
        String entityName,
        String tableName,
        String redisNamespace,
        String observationTag,
        String idColumn,
        String versionColumn,
        String deletedColumn,
        String id,
        Map<String, String> columns,
        long version,
        Instant createdAt,
        String dependencyNamespace,
        String dependencyId,
        long dependencyVersion
) {
    public QueuedWriteOperation(
            OperationType type,
            String entityName,
            String tableName,
            String redisNamespace,
            String observationTag,
            String idColumn,
            String versionColumn,
            String deletedColumn,
            String id,
            Map<String, String> columns,
            long version,
            Instant createdAt
    ) {
        this(
                type, entityName, tableName, redisNamespace, observationTag,
                idColumn, versionColumn, deletedColumn, id, columns, version,
                createdAt, null, null, 0L
        );
    }

    public boolean hasDependency() {
        return dependencyNamespace != null && !dependencyNamespace.isBlank()
                && dependencyId != null && !dependencyId.isBlank()
                && dependencyVersion > 0;
    }
}

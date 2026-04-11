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
        Instant createdAt
) {
}

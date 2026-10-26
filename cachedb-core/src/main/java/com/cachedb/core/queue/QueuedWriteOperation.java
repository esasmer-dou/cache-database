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
        long dependencyVersion,
        String transactionGroupId,
        java.util.List<QueuedWriteOperation> batchOperations,
        Map<String, String> traceMetadata) {
    public QueuedWriteOperation(OperationType type, String entityName, String tableName, String redisNamespace,
            String observationTag, String idColumn, String versionColumn, String deletedColumn, String id,
            Map<String, String> columns, long version, Instant createdAt, String dependencyNamespace,
            String dependencyId, long dependencyVersion, String transactionGroupId, java.util.List<QueuedWriteOperation> batchOperations) {
        this(type, entityName, tableName, redisNamespace, observationTag, idColumn, versionColumn,
                deletedColumn, id, columns, version, createdAt, dependencyNamespace, dependencyId,
                dependencyVersion, transactionGroupId, batchOperations, Map.of());
    }
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
            Instant createdAt,
            String dependencyNamespace,
            String dependencyId,
            long dependencyVersion) {
        this(
                type,
                entityName,
                tableName,
                redisNamespace,
                observationTag,
                idColumn,
                versionColumn,
                deletedColumn,
                id,
                columns,
                version,
                createdAt,
                dependencyNamespace,
                dependencyId,
                dependencyVersion,
                null,
                java.util.List.of());
    }

    public QueuedWriteOperation {
        traceMetadata = com.reactor.cachedb.core.trace.TraceContexts.boundedCopy(traceMetadata);
        batchOperations =
                batchOperations == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(batchOperations);
        if (!batchOperations.isEmpty()) {
            if (transactionGroupId == null
                    || transactionGroupId.isBlank()
                    || batchOperations.size() > 1000)
                throw new IllegalArgumentException("Invalid atomic write envelope");
            for (var member : batchOperations)
                if (member.atomicBatch() || !transactionGroupId.equals(member.transactionGroupId()))
                    throw new IllegalArgumentException("Nested or mismatched atomic batch member");
        }
    }

    public boolean atomicBatch() {
        return !batchOperations.isEmpty();
    }

    public QueuedWriteOperation inTransaction(String group) {
        return new QueuedWriteOperation(
                type,
                entityName,
                tableName,
                redisNamespace,
                observationTag,
                idColumn,
                versionColumn,
                deletedColumn,
                id,
                columns,
                version,
                createdAt,
                dependencyNamespace,
                dependencyId,
                dependencyVersion,
                group,
                java.util.List.of(), traceMetadata);
    }

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
            Instant createdAt) {
        this(
                type,
                entityName,
                tableName,
                redisNamespace,
                observationTag,
                idColumn,
                versionColumn,
                deletedColumn,
                id,
                columns,
                version,
                createdAt,
                null,
                null,
                0L);
    }

    public boolean hasDependency() {
        return dependencyNamespace != null
                && !dependencyNamespace.isBlank()
                && dependencyId != null
                && !dependencyId.isBlank()
                && dependencyVersion > 0;
    }
}

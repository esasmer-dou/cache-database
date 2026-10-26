package com.reactor.cachedb.core.model;

import java.time.Instant;
import java.util.Map;

public record WriteOperation<T, ID>(
        OperationType type,
        EntityMetadata<T, ID> metadata,
        ID id,
        Map<String, Object> columns,
        String redisPayload,
        long version,
        Instant createdAt,
        WriteDependency dependency,
        Map<String, String> traceMetadata
) {
    public WriteOperation {
        traceMetadata = com.reactor.cachedb.core.trace.TraceContexts.boundedCopy(traceMetadata);
    }

    /** Versioned predecessors must never disappear into state compaction. */
    public java.util.Optional<WriteOperation<T, ID>> coalescePatch(WriteOperation<T, ID> next) {
        if (next == null || metadata.versionColumn() != null || next.metadata().versionColumn() != null
                || dependency != null || next.dependency() != null
                || next.type() != OperationType.PATCH || type == OperationType.DELETE
                || next.version() <= version || !java.util.Objects.equals(id, next.id())
                || !metadata.entityName().equals(next.metadata().entityName())
                || !metadata.redisNamespace().equals(next.metadata().redisNamespace())
                || !metadata.tableName().equals(next.metadata().tableName())
                || type == OperationType.UPSERT && (next.redisPayload() == null || next.redisPayload().isEmpty()))
            return java.util.Optional.empty();
        var merged = new java.util.LinkedHashMap<>(columns);
        merged.putAll(next.columns());
        return java.util.Optional.of(new WriteOperation<>(type, metadata, id,
                java.util.Collections.unmodifiableMap(merged), next.redisPayload(), next.version(),
                next.createdAt(), dependency, next.traceMetadata()));
    }

    public WriteOperation(OperationType type, EntityMetadata<T, ID> metadata, ID id,
            Map<String, Object> columns, String redisPayload, long version, Instant createdAt, WriteDependency dependency) {
        this(type, metadata, id, columns, redisPayload, version, createdAt, dependency,
                com.reactor.cachedb.core.trace.TraceContexts.current());
    }
    public WriteOperation(
            OperationType type,
            EntityMetadata<T, ID> metadata,
            ID id,
            Map<String, Object> columns,
            String redisPayload,
            long version,
            Instant createdAt
    ) {
        this(type, metadata, id, columns, redisPayload, version, createdAt, null);
    }
}

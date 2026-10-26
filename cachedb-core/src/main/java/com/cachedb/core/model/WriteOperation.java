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

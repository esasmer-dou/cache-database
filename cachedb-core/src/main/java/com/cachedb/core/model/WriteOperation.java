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
        Instant createdAt
) {
}

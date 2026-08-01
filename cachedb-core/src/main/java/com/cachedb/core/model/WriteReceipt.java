package com.reactor.cachedb.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Confirms Redis acceptance. SQL durability remains asynchronous until the
 * matching durability marker reaches this receipt's version.
 */
public record WriteReceipt<T, ID>(
        T entity,
        ID id,
        String redisNamespace,
        OperationType operationType,
        long version,
        Instant acceptedAt
) {
    public WriteReceipt {
        Objects.requireNonNull(id, "id");
        if (redisNamespace == null || redisNamespace.isBlank()) {
            throw new IllegalArgumentException("redisNamespace must not be blank");
        }
        Objects.requireNonNull(operationType, "operationType");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be greater than zero");
        }
        acceptedAt = acceptedAt == null ? Instant.now() : acceptedAt;
    }

    public WriteDependency asDependency() {
        return new WriteDependency(redisNamespace, String.valueOf(id), version);
    }
}

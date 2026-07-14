package com.reactor.cachedb.core.page;

import java.util.Objects;

public record VersionedEntity<T>(T entity, long version) {

    public VersionedEntity {
        Objects.requireNonNull(entity, "entity");
        if (version <= 0L) {
            throw new IllegalArgumentException("version must be greater than zero");
        }
    }
}

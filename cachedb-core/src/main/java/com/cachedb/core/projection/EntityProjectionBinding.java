package com.reactor.cachedb.core.projection;

public record EntityProjectionBinding<T, P, ID>(
        String entityName,
        EntityProjection<T, P, ID> projection
) {
}

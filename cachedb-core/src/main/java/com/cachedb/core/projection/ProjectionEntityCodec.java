package com.reactor.cachedb.core.projection;

import com.reactor.cachedb.core.model.EntityCodec;

import java.util.Map;

public final class ProjectionEntityCodec<P, ID> implements EntityCodec<P> {

    private final EntityProjection<?, P, ID> projection;

    public ProjectionEntityCodec(EntityProjection<?, P, ID> projection) {
        this.projection = projection;
    }

    @Override
    public String toRedisValue(P entity) {
        return projection.codec().toRedisValue(entity);
    }

    @Override
    public P fromRedisValue(String encoded) {
        return projection.codec().fromRedisValue(encoded);
    }

    @Override
    public Map<String, Object> toColumns(P entity) {
        return projection.columnExtractor().apply(entity);
    }
}

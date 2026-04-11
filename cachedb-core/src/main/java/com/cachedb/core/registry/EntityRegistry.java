package com.reactor.cachedb.core.registry;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import com.reactor.cachedb.core.relation.RelationBatchLoader;

import java.util.Collection;
import java.util.Optional;

public interface EntityRegistry {
    <T, ID> EntityBinding<T, ID> register(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RelationBatchLoader<T> relationBatchLoader,
            EntityPageLoader<T> pageLoader
    );
    <T, ID, P> EntityProjectionBinding<T, P, ID> registerProjection(
            EntityMetadata<T, ID> metadata,
            EntityProjection<T, P, ID> projection
    );
    Optional<EntityBinding<?, ?>> find(String entityName);
    Optional<EntityProjectionBinding<?, ?, ?>> findProjection(String entityName, String projectionName);
    Collection<EntityProjectionBinding<?, ?, ?>> projections(String entityName);
    Collection<EntityBinding<?, ?>> all();
}

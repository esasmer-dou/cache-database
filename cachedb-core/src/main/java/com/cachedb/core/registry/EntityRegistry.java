package com.reactor.cachedb.core.registry;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.page.EntityByIdLoader;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.EntityQueryLoader;
import com.reactor.cachedb.core.page.NoOpEntityByIdLoader;
import com.reactor.cachedb.core.page.NoOpEntityQueryLoader;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import com.reactor.cachedb.core.relation.RelationBatchLoader;

import java.util.Collection;
import java.util.Optional;

public interface EntityRegistry {
    default <T, ID> EntityBinding<T, ID> register(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RelationBatchLoader<T> relationBatchLoader,
            EntityPageLoader<T> pageLoader
    ) {
        return register(
                metadata,
                codec,
                cachePolicy,
                relationBatchLoader,
                pageLoader,
                new NoOpEntityByIdLoader<>(),
                new NoOpEntityQueryLoader<>()
        );
    }

    default <T, ID> EntityBinding<T, ID> register(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RelationBatchLoader<T> relationBatchLoader,
            EntityPageLoader<T> pageLoader,
            EntityByIdLoader<T, ID> byIdLoader,
            EntityQueryLoader<T> queryLoader
    ) {
        throw new UnsupportedOperationException("Entity registration is not supported by this registry implementation");
    }

    <T, ID, P> EntityProjectionBinding<T, P, ID> registerProjection(
            EntityMetadata<T, ID> metadata,
            EntityProjection<T, P, ID> projection
    );
    Optional<EntityBinding<?, ?>> find(String entityName);
    Optional<EntityProjectionBinding<?, ?, ?>> findProjection(String entityName, String projectionName);
    Collection<EntityProjectionBinding<?, ?, ?>> projections(String entityName);
    Collection<EntityBinding<?, ?>> all();
    /** Administrative opt-in bindings; custom registries must preserve the explicit 10000 bound. */
    default Collection<EntityBinding<?,?>> tenantProtectedBindings(){
        var result=new java.util.ArrayList<EntityBinding<?,?>>();int examined=0;
        for(var binding:all()){
            if(++examined>10000)throw new IllegalStateException("Protection registry exceeds bounded validation");
            if(binding.metadata().tenantPurgeEnabled())result.add(binding);
        }
        return java.util.List.copyOf(result);
    }
}

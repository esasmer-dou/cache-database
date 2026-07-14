package com.reactor.cachedb.core.registry;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.page.EntityByIdLoader;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.core.page.EntityQueryLoader;
import com.reactor.cachedb.core.relation.RelationBatchLoader;

public record EntityBinding<T, ID>(
        EntityMetadata<T, ID> metadata,
        EntityCodec<T> codec,
        CachePolicy cachePolicy,
        RelationBatchLoader<T> relationBatchLoader,
        EntityPageLoader<T> pageLoader,
        EntityByIdLoader<T, ID> byIdLoader,
        EntityQueryLoader<T> queryLoader
) {
    public EntityBinding(
            EntityMetadata<T, ID> metadata,
            EntityCodec<T> codec,
            CachePolicy cachePolicy,
            RelationBatchLoader<T> relationBatchLoader,
            EntityPageLoader<T> pageLoader
    ) {
        this(
                metadata,
                codec,
                cachePolicy,
                relationBatchLoader,
                pageLoader,
                new com.reactor.cachedb.core.page.NoOpEntityByIdLoader<>(),
                new com.reactor.cachedb.core.page.NoOpEntityQueryLoader<>()
        );
    }
}

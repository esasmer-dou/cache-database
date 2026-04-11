package com.reactor.cachedb.core.api;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.registry.EntityBinding;

public interface CacheSession {
    <T, ID> EntityRepository<T, ID> repository(EntityMetadata<T, ID> metadata, EntityCodec<T> codec);
    <T, ID> EntityRepository<T, ID> repository(EntityMetadata<T, ID> metadata, EntityCodec<T> codec, CachePolicy cachePolicy);

    default <T, ID> EntityRepository<T, ID> repository(EntityBinding<T, ID> binding) {
        return repository(binding.metadata(), binding.codec(), binding.cachePolicy());
    }
}

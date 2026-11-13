package com.reactor.cachedb.reactor;

import com.reactor.cachedb.core.api.AsyncEntityRepository;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import java.util.Objects;

/** Stateless factory; does not own/create an executor or cache repository instances. */
public final class ReactiveEntityRepositoryFactory {
    public <T, ID> ReactiveEntityRepository<T, ID> from(AsyncEntityRepository<T, ID> repository) {
        return new ReactiveEntityRepository<>(repository);
    }
    public <T, ID> ReactiveEntityRepository<T, ID> from(EntityRepository<T, ID> repository) {
        return from(Objects.requireNonNull(repository, "repository").async());
    }
    public <T, ID> ReactiveEntityRepository<T, ID> from(EntityRepository<T, ID> repository, CacheAsyncExecutor executor) {
        return from(Objects.requireNonNull(repository, "repository").async(executor));
    }
}

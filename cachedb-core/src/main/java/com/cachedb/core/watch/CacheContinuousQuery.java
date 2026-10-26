package com.reactor.cachedb.core.watch;

import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.query.QuerySpec;
import java.util.Objects;

/** Explicit lifecycle handle for local, bounded predicate notifications. */
public record CacheContinuousQuery(QuerySpec spec) {
    public CacheContinuousQuery { Objects.requireNonNull(spec); }
    public <T, ID> CacheWatcherRegistration register(EntityRepository<T, ID> repository, CacheWatcherListener<T, ID> listener) {
        return repository.registerWatcher(spec, listener);
    }
}

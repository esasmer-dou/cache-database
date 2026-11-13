package com.reactor.cachedb.core.async;

import com.reactor.cachedb.core.api.AsyncEntityRepository;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.query.AggregateResult;
import com.reactor.cachedb.core.query.AggregateSpec;
import com.reactor.cachedb.core.query.FacetAccumulator;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.repository.HotLookup;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Correct bounded fallback; not the Redis T-1 direct-enqueue implementation. */
public final class OffloadingAsyncEntityRepository<T, ID> implements AsyncEntityRepository<T, ID> {
    private final EntityRepository<T, ID> delegate;
    private final CacheAsyncExecutor executor;

    public OffloadingAsyncEntityRepository(EntityRepository<T, ID> delegate, CacheAsyncExecutor executor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override public CompletableFuture<Optional<T>> findByIdAsync(ID id) {
        Objects.requireNonNull(id, "id");
        return executor.submit(() -> delegate.findById(id));
    }
    @Override public CompletableFuture<HotLookup<T>> findHotByIdAsync(ID id) {
        Objects.requireNonNull(id, "id");
        return executor.submit(() -> delegate.findHotById(id));
    }
    @Override public CompletableFuture<List<T>> findAllAsync(Collection<ID> ids) {
        var copy = AsyncRepositoryBounds.copy(ids, QuerySpec.MAX_LIMIT);
        return executor.submit(() -> AsyncRepositoryBounds.copy(delegate.findAll(copy), copy.size()));
    }
    @Override public CompletableFuture<T> saveAsync(T entity) {
        Objects.requireNonNull(entity, "entity");
        return executor.submit(() -> delegate.save(entity));
    }
    @Override public CompletableFuture<Void> deleteAsync(ID id) {
        Objects.requireNonNull(id, "id");
        return executor.submit(() -> { delegate.deleteById(id); return null; });
    }
    @Override public CompletableFuture<List<T>> queryAsync(QuerySpec spec) {
        Objects.requireNonNull(spec, "spec");
        return executor.submit(() -> AsyncRepositoryBounds.copy(delegate.query(spec), spec.limit()));
    }
    @Override public CompletableFuture<AggregateResult> aggregateAsync(AggregateSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return executor.submit(() -> delegate.aggregate(spec));
    }
    @Override public CompletableFuture<Map<String, AggregateResult>> aggregateByAsync(String column, AggregateSpec spec) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(spec, "spec");
        return executor.submit(() -> {
            var result = delegate.aggregateBy(column, spec);
            if (result.size() > FacetAccumulator.MAX_GROUPS) throw new IllegalStateException("Async facet limit exceeded");
            return Collections.unmodifiableMap(new LinkedHashMap<>(result));
        });
    }
}

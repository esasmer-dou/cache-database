package com.reactor.cachedb.core.async;

import com.reactor.cachedb.core.model.WriteDependency;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.query.AggregateResult;
import com.reactor.cachedb.core.query.AggregateSpec;
import com.reactor.cachedb.core.query.FacetAccumulator;
import com.reactor.cachedb.core.repository.AsyncCacheDbRepository;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import com.reactor.cachedb.core.repository.HotLookup;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Does not synthesize receipts, treat hot misses as source reads, or wait for SQL durability. */
public final class OffloadingAsyncCacheDbRepository<T, ID> implements AsyncCacheDbRepository<T, ID> {
    private final CacheDbRepository<T, ID> delegate;
    private final CacheAsyncExecutor executor;

    public OffloadingAsyncCacheDbRepository(CacheDbRepository<T, ID> delegate, CacheAsyncExecutor executor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.executor = Objects.requireNonNull(executor, "executor");
    }
    @Override public CompletableFuture<HotLookup<T>> findHotByIdAsync(ID id) {
        Objects.requireNonNull(id, "id");
        return executor.submit(() -> delegate.findHotById(id));
    }
    @Override public CompletableFuture<Optional<T>> findSourceByIdAsync(ID id) {
        Objects.requireNonNull(id, "id");
        return executor.submit(() -> delegate.findSourceById(id));
    }
    @Override public CompletableFuture<WriteReceipt<T, ID>> saveAsync(T entity) {
        Objects.requireNonNull(entity, "entity");
        return executor.submit(() -> delegate.save(entity));
    }
    @Override public CompletableFuture<WriteReceipt<T, ID>> saveAsync(T entity, long expectedVersion) {
        Objects.requireNonNull(entity, "entity");
        return executor.submit(() -> delegate.save(entity, expectedVersion));
    }
    @Override public CompletableFuture<WriteReceipt<T, ID>> saveAfterAsync(T entity, WriteDependency dependency) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(dependency, "dependency");
        return executor.submit(() -> delegate.saveAfter(entity, dependency));
    }
    @Override public CompletableFuture<List<WriteReceipt<T, ID>>> saveAllAsync(Collection<T> entities) {
        var copy = AsyncRepositoryBounds.copy(entities, 1000);
        return executor.submit(() -> AsyncRepositoryBounds.copy(delegate.saveAll(copy), 1000));
    }
    @Override public CompletableFuture<WriteReceipt<T, ID>> deleteAsync(ID id) {
        Objects.requireNonNull(id, "id");
        return executor.submit(() -> delegate.deleteById(id));
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

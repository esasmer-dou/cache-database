package com.reactor.cachedb.core.repository;

import com.reactor.cachedb.core.model.WriteDependency;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.query.AggregateResult;
import com.reactor.cachedb.core.query.AggregateSpec;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Application repository adapter: hot/source reads stay separate; actual write receipts stay intact. */
public interface AsyncCacheDbRepository<T, ID> {
    CompletableFuture<HotLookup<T>> findHotByIdAsync(ID id);
    CompletableFuture<Optional<T>> findSourceByIdAsync(ID id);
    CompletableFuture<WriteReceipt<T, ID>> saveAsync(T entity);
    CompletableFuture<WriteReceipt<T, ID>> saveAsync(T entity, long expectedVersion);
    CompletableFuture<WriteReceipt<T, ID>> saveAfterAsync(T entity, WriteDependency dependency);
    CompletableFuture<List<WriteReceipt<T, ID>>> saveAllAsync(Collection<T> entities);
    CompletableFuture<WriteReceipt<T, ID>> deleteAsync(ID id);
    CompletableFuture<Map<String, AggregateResult>> aggregateByAsync(String column, AggregateSpec spec);
}

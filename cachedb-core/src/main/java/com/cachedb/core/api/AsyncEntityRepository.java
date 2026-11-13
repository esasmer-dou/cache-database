package com.reactor.cachedb.core.api;

import com.reactor.cachedb.core.query.AggregateResult;
import com.reactor.cachedb.core.query.AggregateSpec;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.repository.HotLookup;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * EntityRepository semantics: save returns the entity, delete returns no receipt.
 * Implementations capture tenant/causal/configured trace on each invocation, never at construction.
 * Cancellation does not roll back writes or prove that storage I/O has stopped.
 */
public interface AsyncEntityRepository<T, ID> {
    CompletableFuture<Optional<T>> findByIdAsync(ID id);
    CompletableFuture<HotLookup<T>> findHotByIdAsync(ID id);
    CompletableFuture<List<T>> findAllAsync(Collection<ID> ids);
    CompletableFuture<T> saveAsync(T entity);
    CompletableFuture<Void> deleteAsync(ID id);
    CompletableFuture<List<T>> queryAsync(QuerySpec spec);
    CompletableFuture<AggregateResult> aggregateAsync(AggregateSpec spec);
    CompletableFuture<Map<String, AggregateResult>> aggregateByAsync(String column, AggregateSpec spec);
}

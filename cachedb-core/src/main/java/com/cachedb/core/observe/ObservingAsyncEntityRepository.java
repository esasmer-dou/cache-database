package com.reactor.cachedb.core.observe;

import com.reactor.cachedb.core.api.AsyncEntityRepository;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.repository.HotLookup;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Delegates the async API without replacing native execution with an offloader. */
public final class ObservingAsyncEntityRepository<T, ID> implements AsyncEntityRepository<T, ID> {
    private final AsyncEntityRepository<T, ID> delegate;
    private final String entity;
    private final CacheObservationRecorder recorder;
    private final Object operationOwner;
    private final com.reactor.cachedb.core.trace.TraceContextPropagator writeTrace;
    private ObservingAsyncEntityRepository(AsyncEntityRepository<T, ID> delegate, String entity, CacheObservationRecorder recorder, Object operationOwner,
            com.reactor.cachedb.core.trace.TraceContextPropagator writeTrace) {
        this.delegate = delegate; this.entity = Objects.requireNonNull(entity, "entity"); this.recorder = recorder;
        this.operationOwner = operationOwner; this.writeTrace = writeTrace;
    }
    public static <T, ID> AsyncEntityRepository<T, ID> wrap(AsyncEntityRepository<T, ID> delegate, String entity, CacheObservationRecorder recorder) {
        return wrap(delegate, entity, recorder, null);
    }
    public static <T, ID> AsyncEntityRepository<T, ID> wrap(AsyncEntityRepository<T, ID> delegate, String entity,
            CacheObservationRecorder recorder, Object operationOwner) {
        return wrap(delegate, entity, recorder, operationOwner, null);
    }
    public static <T, ID> AsyncEntityRepository<T, ID> wrap(AsyncEntityRepository<T, ID> delegate, String entity,
            CacheObservationRecorder recorder, Object operationOwner,
            com.reactor.cachedb.core.trace.TraceContextPropagator writeTrace) {
        Objects.requireNonNull(delegate, "delegate"); Objects.requireNonNull(recorder, "recorder");
        if (!recorder.enabled()) return delegate;
        if (delegate instanceof ObservingAsyncEntityRepository<?, ?> observed) {
            if (observed.recorder != recorder || !observed.entity.equals(entity) || observed.operationOwner != operationOwner || observed.writeTrace != writeTrace)
                throw new IllegalArgumentException("Async repository already has a different observation boundary");
            return delegate;
        }
        return new ObservingAsyncEntityRepository<>(delegate, entity, recorder, operationOwner, writeTrace);
    }
    private <V> CompletableFuture<V> call(CacheOperation operation, Supplier<CompletableFuture<V>> action) {
        return CacheObservations.callAsync(recorder, operation, entity, operationOwner, () -> {
            if (writeTrace == null || operation != CacheOperation.SAVE && operation != CacheOperation.DELETE)
                return action.get();
            try (var trace = com.reactor.cachedb.core.trace.TraceContexts.withMetadata(writeTrace.capture())) {
                return action.get();
            }
        });
    }
    @Override public CompletableFuture<Optional<T>> findByIdAsync(ID id) {
        return call(CacheOperation.FIND_BY_ID, () -> delegate.findByIdAsync(id));
    }
    @Override public CompletableFuture<HotLookup<T>> findHotByIdAsync(ID id) {
        return call(CacheOperation.FIND_HOT_BY_ID, () -> delegate.findHotByIdAsync(id));
    }
    @Override public CompletableFuture<List<T>> findAllAsync(Collection<ID> ids) {
        return call(CacheOperation.FIND_ALL, () -> delegate.findAllAsync(ids));
    }
    @Override public CompletableFuture<T> saveAsync(T row) { return call(CacheOperation.SAVE, () -> delegate.saveAsync(row)); }
    @Override public CompletableFuture<Void> deleteAsync(ID id) { return call(CacheOperation.DELETE, () -> delegate.deleteAsync(id)); }
    @Override public CompletableFuture<List<T>> queryAsync(QuerySpec spec) { return call(CacheOperation.QUERY, () -> delegate.queryAsync(spec)); }
    @Override public CompletableFuture<AggregateResult> aggregateAsync(AggregateSpec spec) {
        return call(CacheOperation.AGGREGATE, () -> delegate.aggregateAsync(spec));
    }
    @Override public CompletableFuture<Map<String, AggregateResult>> aggregateByAsync(String column, AggregateSpec spec) {
        return call(CacheOperation.AGGREGATE_BY, () -> delegate.aggregateByAsync(column, spec));
    }
}

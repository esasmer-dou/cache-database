package com.reactor.cachedb.reactor;

import com.reactor.cachedb.core.api.AsyncEntityRepository;
import com.reactor.cachedb.core.async.AsyncRepositoryBounds;
import com.reactor.cachedb.core.query.AggregateResult;
import com.reactor.cachedb.core.query.AggregateSpec;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.repository.HotLookup;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Cold, per-subscription delegation. No scheduler, blocking call, hidden retry, or unbounded stream. */
public final class ReactiveEntityRepository<T, ID> {
    private final AsyncEntityRepository<T, ID> delegate;

    public ReactiveEntityRepository(AsyncEntityRepository<T, ID> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    private <V> Mono<V> call(Supplier<CompletableFuture<V>> operation) {
        return Mono.deferContextual(context ->
                Mono.fromFuture(() -> CacheReactorContext.call(context, operation)));
    }

    public Mono<T> findById(ID id) {
        return call(() -> delegate.findByIdAsync(id)).flatMap(Mono::justOrEmpty);
    }
    public Mono<HotLookup<T>> findHotById(ID id) {
        return call(() -> delegate.findHotByIdAsync(id));
    }
    /** IDs are copied at assembly; each subscription starts a fresh bounded operation. */
    public Flux<T> findAll(Collection<ID> ids) {
        var copy = AsyncRepositoryBounds.copy(ids, QuerySpec.MAX_LIMIT);
        return call(() -> delegate.findAllAsync(copy)).flatMapMany(rows -> {
            if (rows.size() > copy.size()) return Flux.error(new IllegalStateException("Async bulk result limit exceeded"));
            return Flux.fromIterable(rows);
        });
    }
    public Mono<T> save(T entity) { return call(() -> delegate.saveAsync(entity)); }
    public Mono<Void> delete(ID id) { return call(() -> delegate.deleteAsync(id)); }
    public Flux<T> query(QuerySpec spec) {
        Objects.requireNonNull(spec, "spec");
        return call(() -> delegate.queryAsync(spec)).flatMapMany(rows -> {
            if (rows.size() > spec.limit()) return Flux.error(new IllegalStateException("Async query result limit exceeded"));
            return Flux.fromIterable(rows);
        });
    }
    public Mono<AggregateResult> aggregate(AggregateSpec spec) {
        return call(() -> delegate.aggregateAsync(spec));
    }
    public Mono<Map<String, AggregateResult>> aggregateBy(String column, AggregateSpec spec) {
        return call(() -> delegate.aggregateByAsync(column, spec)).map(groups -> {
            if (groups.size() > com.reactor.cachedb.core.query.FacetAccumulator.MAX_GROUPS)
                throw new IllegalStateException("Async facet result limit exceeded");
            return groups;
        });
    }
}

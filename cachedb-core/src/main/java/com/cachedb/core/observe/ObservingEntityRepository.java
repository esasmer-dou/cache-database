package com.reactor.cachedb.core.observe;

import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.page.VersionedEntity;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.watch.*;
import java.util.*;

/** Explicit decorator preserving repository overloads, views and capabilities. */
public final class ObservingEntityRepository<T, ID> implements EntityRepository<T, ID> {
    private final EntityRepository<T, ID> delegate;
    private final String entity;
    private final CacheObservationRecorder recorder;
    private ObservingEntityRepository(EntityRepository<T, ID> delegate, String entity, CacheObservationRecorder recorder) {
        this.delegate = delegate; this.entity = Objects.requireNonNull(entity, "entity"); this.recorder = recorder;
    }
    public static <T, ID> EntityRepository<T, ID> wrap(EntityRepository<T, ID> delegate, String entity, CacheObservationRecorder recorder) {
        Objects.requireNonNull(delegate, "delegate"); Objects.requireNonNull(recorder, "recorder");
        if (!recorder.enabled()) return delegate;
        if (delegate instanceof ObservingEntityRepository<?, ?> observed) {
            if (observed.recorder != recorder || !observed.entity.equals(entity))
                throw new IllegalArgumentException("Repository already has a different observation boundary");
            return delegate;
        }
        return new ObservingEntityRepository<>(delegate, entity, recorder);
    }
    private <V> V call(CacheOperation operation, java.util.function.Supplier<V> action) {
        return CacheObservations.call(recorder, operation, entity, action);
    }
    private EntityRepository<T, ID> view(EntityRepository<T, ID> view) {
        return view == delegate ? this : wrap(view, entity, recorder);
    }
    @Override public RepositoryCapabilities capabilities() { return delegate.capabilities(); }
    @Override public EntityQueryCapacity entityQueryCapacity() { return delegate.entityQueryCapacity(); }
    @Override public CacheAsyncExecutor asyncExecutor() { return delegate.asyncExecutor(); }
    @Override public AsyncEntityRepository<T, ID> async() {
        return ObservingAsyncEntityRepository.wrap(delegate.async(), entity, recorder);
    }
    @Override public AsyncEntityRepository<T, ID> async(CacheAsyncExecutor executor) {
        return ObservingAsyncEntityRepository.wrap(delegate.async(executor), entity, recorder);
    }
    @Override public Optional<T> findById(ID id) { return call(CacheOperation.FIND_BY_ID, () -> delegate.findById(id)); }
    @Override public HotLookup<T> findHotById(ID id) { return call(CacheOperation.FIND_HOT_BY_ID, () -> delegate.findHotById(id)); }
    @Override public Optional<VersionedEntity<T>> findVersionedById(ID id) {
        return call(CacheOperation.FIND_VERSIONED_BY_ID, () -> delegate.findVersionedById(id));
    }
    @Override public List<T> findAll(Collection<ID> ids) { return call(CacheOperation.FIND_ALL, () -> delegate.findAll(ids)); }
    @Override public List<T> findPage(PageWindow window) { return call(CacheOperation.FIND_PAGE, () -> delegate.findPage(window)); }
    @Override public QueryExplainPlan explain(QuerySpec spec) { return call(CacheOperation.EXPLAIN, () -> delegate.explain(spec)); }
    @Override public List<T> query(QuerySpec spec) { return call(CacheOperation.QUERY, () -> delegate.query(spec)); }
    @Override public List<T> query(QueryNode node) { return call(CacheOperation.QUERY, () -> delegate.query(node)); }
    @Override public List<T> query(QueryNode node, QuerySort... sorts) {
        return call(CacheOperation.QUERY, () -> delegate.query(node, sorts));
    }
    @Override public List<T> query(QueryNode node, int limit, QuerySort... sorts) {
        return call(CacheOperation.QUERY, () -> delegate.query(node, limit, sorts));
    }
    @Override public List<T> query(QueryFilter filter, FetchPlan plan, QuerySort... sorts) {
        return call(CacheOperation.QUERY, () -> delegate.query(filter, plan, sorts));
    }
    @Override public long count(QuerySpec spec) { return call(CacheOperation.COUNT, () -> delegate.count(spec)); }
    @Override public AggregateResult aggregate(AggregateSpec spec) { return call(CacheOperation.AGGREGATE, () -> delegate.aggregate(spec)); }
    @Override public Map<String, AggregateResult> aggregateBy(String column, AggregateSpec spec) {
        return call(CacheOperation.AGGREGATE_BY, () -> delegate.aggregateBy(column, spec));
    }
    @Override public Object aggregateFieldValue(T row, String column) { return delegate.aggregateFieldValue(row, column); }
    @Override public T save(T row) { return call(CacheOperation.SAVE, () -> delegate.save(row)); }
    @Override public WriteReceipt<T, ID> save(T row, long version) { return call(CacheOperation.SAVE, () -> delegate.save(row, version)); }
    @Override public WriteReceipt<T, ID> saveWithReceipt(T row) { return call(CacheOperation.SAVE, () -> delegate.saveWithReceipt(row)); }
    @Override public WriteReceipt<T, ID> saveAfter(T row, WriteDependency dependency) {
        return call(CacheOperation.SAVE, () -> delegate.saveAfter(row, dependency));
    }
    @Override public List<WriteReceipt<T, ID>> saveAll(Collection<T> rows) {
        return call(CacheOperation.SAVE_ALL, () -> delegate.saveAll(rows));
    }
    @Override public T patch(ID id, Map<String, Object> changes) { return call(CacheOperation.PATCH, () -> delegate.patch(id, changes)); }
    @Override public WriteReceipt<T, ID> patchWithReceipt(ID id, Map<String, Object> changes) {
        return call(CacheOperation.PATCH, () -> delegate.patchWithReceipt(id, changes));
    }
    @Override public void deleteById(ID id) { call(CacheOperation.DELETE, () -> { delegate.deleteById(id); return null; }); }
    @Override public WriteReceipt<T, ID> deleteWithReceipt(ID id) {
        return call(CacheOperation.DELETE, () -> delegate.deleteWithReceipt(id));
    }
    @Override public List<WriteReceipt<T, ID>> bulkDelete(Collection<ID> ids) {
        return call(CacheOperation.DELETE, () -> delegate.bulkDelete(ids));
    }
    @Override public <K> Map<K, List<T>> queryPartitions(PartitionedQuerySpec<K> spec) {
        return call(CacheOperation.QUERY_PARTITIONS, () -> delegate.queryPartitions(spec));
    }
    @Override public EntityRepository<T, ID> withFetchPlan(FetchPlan plan) { return view(delegate.withFetchPlan(plan)); }
    @Override public EntityRepository<T, ID> withRelations(String... relations) { return view(delegate.withRelations(relations)); }
    @Override public EntityRepository<T, ID> withRelationLimit(String relation, int limit) {
        return view(delegate.withRelationLimit(relation, limit));
    }
    @Override public <P> ProjectionRepository<P, ID> projected(EntityProjection<T, P, ID> projection) {
        return ObservingProjectionRepository.wrap(delegate.projected(projection), entity, recorder);
    }
    @Override public CacheWatcherRegistration registerWatcher(QuerySpec spec, CacheWatcherListener<T, ID> listener) {
        return delegate.registerWatcher(spec, listener);
    }
}

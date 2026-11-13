package com.reactor.cachedb.core.api;

import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.model.WriteDependency;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.page.VersionedEntity;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.projection.EntityProjection;
import com.reactor.cachedb.core.query.PartitionedQuerySpec;
import com.reactor.cachedb.core.query.QueryFilter;
import com.reactor.cachedb.core.query.QueryNode;
import com.reactor.cachedb.core.query.QueryExplainPlan;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.core.repository.HotLookup;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface EntityRepository<T, ID> {
    /** Runtime-owned gate; legacy/custom implementations may instead call async(executor). */
    default com.reactor.cachedb.core.async.CacheAsyncExecutor asyncExecutor() {
        throw new UnsupportedOperationException("No runtime-owned async executor; use async(executor)");
    }

    default AsyncEntityRepository<T, ID> async() {
        return async(asyncExecutor());
    }

    /** Redis runtimes override this overload for non-parking T-1 direct enqueue. */
    default AsyncEntityRepository<T, ID> async(com.reactor.cachedb.core.async.CacheAsyncExecutor executor) {
        return new com.reactor.cachedb.core.async.OffloadingAsyncEntityRepository<>(this, executor);
    }

    default RepositoryCapabilities capabilities() {
        return RepositoryCapabilities.none();
    }

    /**
     * Current full-entity query row capacity, evaluated on the caller without I/O.
     * Legacy/custom repositories default to UNKNOWN: explicit bounded queries keep their
     * existing backend validation, but adapters must not infer unpaged completeness from it.
     * Delegating views must preserve this contract and native query guards remain authoritative.
     */
    default EntityQueryCapacity entityQueryCapacity() {
        return EntityQueryCapacity.unknown();
    }

    Optional<T> findById(ID id);
    default HotLookup<T> findHotById(ID id) {
        return findById(id).map(HotLookup::hit).orElseGet(HotLookup::notCached);
    }
    default Optional<VersionedEntity<T>> findVersionedById(ID id) {
        throw unsupported(RepositoryCapability.VERSIONED_READ);
    }
    List<T> findAll(Collection<ID> ids);
    List<T> findPage(PageWindow pageWindow);
    QueryExplainPlan explain(QuerySpec querySpec);
    List<T> query(QuerySpec querySpec);
    default com.reactor.cachedb.core.watch.CacheWatcherRegistration registerWatcher(QuerySpec spec,
            com.reactor.cachedb.core.watch.CacheWatcherListener<T, ID> listener) {
        throw new UnsupportedOperationException("Continuous query watchers are not supported by this repository");
    }
    default long count(QuerySpec spec) {
        return aggregate(com.reactor.cachedb.core.query.AggregateSpec.count(spec == null ? null : spec.rootGroup())).count();
    }
    default Map<String, com.reactor.cachedb.core.query.AggregateResult> aggregateBy(String groupByColumn,
            com.reactor.cachedb.core.query.AggregateSpec spec) {
        throw new UnsupportedOperationException("aggregateBy is not supported by this repository");
    }
    default com.reactor.cachedb.core.query.AggregateResult aggregate(com.reactor.cachedb.core.query.AggregateSpec spec) {
        var rows = query(QuerySpec.where(spec.filter()).limitTo(QuerySpec.MAX_LIMIT));
        if (rows.size() == QuerySpec.MAX_LIMIT) throw new IllegalStateException("Aggregate may exceed bounded fallback capacity");
        var accumulator = new com.reactor.cachedb.core.query.AggregateResult.Accumulator();
        boolean count = spec.function() == com.reactor.cachedb.core.query.AggregateFunction.COUNT;
        boolean field = spec.fieldName() != null && !spec.fieldName().isBlank() && !spec.fieldName().equals("*");
        for (T row : rows) {
            Object value = field ? aggregateFieldValue(row, spec.fieldName()) : null;
            if (!count || !field || value != null) accumulator.add(count ? null : value);
        }
        return accumulator.result();
    }
    /** Custom repositories can supply explicit field extraction for the bounded fallback. */
    default Object aggregateFieldValue(T entity, String column) {
        throw new UnsupportedOperationException("Custom repository must provide aggregate field extraction");
    }
    T save(T entity);
    /** Returns the merged hot entity; fails before acceptance when a full hot entity is unavailable. */
    default T patch(ID id, Map<String, Object> columnUpdates) {
        throw unsupported(RepositoryCapability.PARTIAL_UPDATE);
    }

    /** Cold SQL-only updates may return a receipt without an entity payload. */
    default WriteReceipt<T, ID> patchWithReceipt(ID id, Map<String, Object> columnUpdates) {
        throw unsupported(RepositoryCapability.PARTIAL_UPDATE);
    }
    default WriteReceipt<T, ID> saveWithReceipt(T entity) {
        throw unsupported(RepositoryCapability.WRITE_RECEIPT);
    }
    default WriteReceipt<T, ID> save(T entity, long expectedVersion) {
        throw unsupported(RepositoryCapability.OPTIMISTIC_WRITE);
    }
    default WriteReceipt<T, ID> saveAfter(T entity, WriteDependency dependency) {
        throw unsupported(RepositoryCapability.DEPENDENCY_AWARE_WRITE);
    }
    default List<WriteReceipt<T, ID>> saveAll(Collection<T> entities) {
        throw unsupported(RepositoryCapability.BULK_WRITE);
    }
    default <K> Map<K, List<T>> queryPartitions(PartitionedQuerySpec<K> querySpec) {
        throw unsupported(RepositoryCapability.PARTITIONED_QUERY);
    }
    void deleteById(ID id);
    default WriteReceipt<T, ID> deleteWithReceipt(ID id) {
        throw unsupported(RepositoryCapability.DELETE_RECEIPT);
    }
    /**
     * Bounded non-transactional delete, at most 10000 input IDs, validated before mutation.
     * Native implementations batch transport and recheck ownership atomically. A failure may
     * report accepted, rejected, uncertain and cleanup-pending IDs; it is never an atomic batch.
     * Legacy/custom repositories fail before mutation unless they explicitly implement this capability.
     */
    default List<WriteReceipt<T, ID>> bulkDelete(Collection<ID> ids) {
        throw unsupported(RepositoryCapability.BULK_DELETE);
    }

    EntityRepository<T, ID> withFetchPlan(FetchPlan fetchPlan);

    default EntityRepository<T, ID> withRelations(String... relations) {
        return withFetchPlan(FetchPlan.of(relations));
    }

    default EntityRepository<T, ID> withRelationLimit(String relationName, int limit) {
        return withFetchPlan(FetchPlan.empty().withRelationLimit(relationName, limit));
    }

    default List<T> query(QueryNode rootNode) {
        return query(QuerySpec.where(rootNode));
    }

    default List<T> query(QueryNode rootNode, QuerySort... sorts) {
        return query(QuerySpec.where(rootNode).orderBy(sorts));
    }

    default List<T> query(QueryNode rootNode, int limit, QuerySort... sorts) {
        return query(QuerySpec.where(rootNode).orderBy(sorts).limitTo(limit));
    }

    default List<T> query(QueryFilter filter, FetchPlan fetchPlan, QuerySort... sorts) {
        return query(QuerySpec.where(filter).orderBy(sorts).fetching(fetchPlan));
    }

    default <P> ProjectionRepository<P, ID> projected(EntityProjection<T, P, ID> projection) {
        throw unsupported(RepositoryCapability.PROJECTION);
    }

    private RepositoryCapabilityUnavailableException unsupported(RepositoryCapability capability) {
        return new RepositoryCapabilityUnavailableException(capability, getClass());
    }
}

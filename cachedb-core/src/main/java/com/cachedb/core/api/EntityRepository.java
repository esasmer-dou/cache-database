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
    default RepositoryCapabilities capabilities() {
        return RepositoryCapabilities.none();
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
    T save(T entity);
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

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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface EntityRepository<T, ID> {
    Optional<T> findById(ID id);
    default Optional<VersionedEntity<T>> findVersionedById(ID id) {
        throw new UnsupportedOperationException("Versioned reads are not supported by this EntityRepository implementation");
    }
    List<T> findAll(Collection<ID> ids);
    List<T> findPage(PageWindow pageWindow);
    QueryExplainPlan explain(QuerySpec querySpec);
    List<T> query(QuerySpec querySpec);
    T save(T entity);
    default WriteReceipt<T, ID> saveWithReceipt(T entity) {
        throw new UnsupportedOperationException("Write receipts are not supported by this EntityRepository implementation");
    }
    default WriteReceipt<T, ID> save(T entity, long expectedVersion) {
        throw new UnsupportedOperationException("Optimistic writes are not supported by this EntityRepository implementation");
    }
    default WriteReceipt<T, ID> saveAfter(T entity, WriteDependency dependency) {
        throw new UnsupportedOperationException("Dependency-aware writes are not supported by this EntityRepository implementation");
    }
    default List<WriteReceipt<T, ID>> saveAll(Collection<T> entities) {
        throw new UnsupportedOperationException("Bulk writes are not supported by this EntityRepository implementation");
    }
    default <K> Map<K, List<T>> queryPartitions(PartitionedQuerySpec<K> querySpec) {
        throw new UnsupportedOperationException("Partitioned queries are not supported by this EntityRepository implementation");
    }
    void deleteById(ID id);
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
        throw new UnsupportedOperationException("Projection repositories are not supported by this EntityRepository implementation");
    }
}

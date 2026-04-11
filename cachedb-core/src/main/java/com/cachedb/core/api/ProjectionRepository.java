package com.reactor.cachedb.core.api;

import com.reactor.cachedb.core.query.QueryNode;
import com.reactor.cachedb.core.query.QuerySort;
import com.reactor.cachedb.core.query.QuerySpec;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectionRepository<P, ID> {
    Optional<P> findById(ID id);
    List<P> findAll(Collection<ID> ids);
    List<P> query(QuerySpec querySpec);

    default List<P> query(QueryNode rootNode) {
        return query(QuerySpec.where(rootNode));
    }

    default List<P> query(QueryNode rootNode, QuerySort... sorts) {
        return query(QuerySpec.where(rootNode).orderBy(sorts));
    }

    default List<P> query(QueryNode rootNode, int limit, QuerySort... sorts) {
        return query(QuerySpec.where(rootNode).orderBy(sorts).limitTo(limit));
    }
}

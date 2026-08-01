package com.reactor.cachedb.core.api;

import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.query.QuerySpec;

import java.util.List;
import java.util.Optional;

/**
 * Explicit durable-source read surface. Reads performed here bypass Redis
 * admission and are intended for bounded archive or recovery routes.
 */
public interface SourceRepository<T, ID> {
    Optional<T> findById(ID id);
    List<T> findPage(PageWindow pageWindow);
    List<T> query(QuerySpec querySpec);
}

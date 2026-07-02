package com.reactor.cachedb.core.page;

import com.reactor.cachedb.core.query.QuerySpec;

import java.util.List;

@FunctionalInterface
public interface EntityQueryLoader<T> {
    List<T> load(QuerySpec querySpec);
}

package com.reactor.cachedb.core.page;

import com.reactor.cachedb.core.query.QuerySpec;

import java.util.List;

public final class NoOpEntityQueryLoader<T> implements EntityQueryLoader<T> {
    @Override
    public List<T> load(QuerySpec querySpec) {
        return List.of();
    }
}

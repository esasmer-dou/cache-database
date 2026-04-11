package com.reactor.cachedb.core.page;

import com.reactor.cachedb.core.cache.PageWindow;

import java.util.List;

public final class NoOpEntityPageLoader<T> implements EntityPageLoader<T> {
    @Override
    public List<T> load(PageWindow pageWindow) {
        return List.of();
    }
}

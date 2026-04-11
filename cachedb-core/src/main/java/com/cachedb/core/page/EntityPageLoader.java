package com.reactor.cachedb.core.page;

import com.reactor.cachedb.core.cache.PageWindow;

import java.util.List;

public interface EntityPageLoader<T> {
    List<T> load(PageWindow pageWindow);
}

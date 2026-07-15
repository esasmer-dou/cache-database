package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.cache.CachePolicyCatalog;

@FunctionalInterface
public interface CachePolicyCatalogCustomizer {
    void customize(CachePolicyCatalog.Builder builder, CacheDbSpringProperties properties);
}

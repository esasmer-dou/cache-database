package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.cache.CachePolicy;

public interface GeneratedCacheBindingsRegistrar {
    String packageName();

    void register(CacheDatabase cacheDatabase);

    void register(CacheDatabase cacheDatabase, CachePolicy cachePolicy);
}

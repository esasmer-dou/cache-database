package com.reactor.cachedb.examples.demo;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.GeneratedCacheBindingsDiscovery;

public final class DemoBindings {

    private DemoBindings() {
    }

    public static void register(CacheDatabase cacheDatabase) {
        register(cacheDatabase, DemoScenarioTuning.fromSystemProperties("cachedb.demo").cachePolicy());
    }

    public static void register(CacheDatabase cacheDatabase, CachePolicy policy) {
        GeneratedCacheBindingsDiscovery.registerDiscovered(
                cacheDatabase,
                policy,
                DemoBindings.class.getClassLoader()
        );
    }
}

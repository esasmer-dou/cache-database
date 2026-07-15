package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.CachePolicyCatalog;

import java.util.List;

public interface GeneratedCacheBindingsRegistrar {
    String packageName();

    void register(CacheDatabase cacheDatabase);

    void register(CacheDatabase cacheDatabase, CachePolicy cachePolicy);

    /**
     * Names produced by this registrar. Older generated registrars return an empty list.
     */
    default List<String> entityNames() {
        return List.of();
    }

    /**
     * Phase one registers metadata, policy and JDBC source loaders for every entity.
     */
    default void registerJdbcSources(CacheDatabase cacheDatabase, CachePolicyCatalog policyCatalog) {
        CachePolicy fallback = cacheDatabase.config().resourceLimits().defaultCachePolicy();
        register(cacheDatabase, fallback);
    }

    /**
     * Phase two wires relation and custom page loaders after all entity sources exist.
     */
    default void registerDeclaredLoaders(CacheDatabase cacheDatabase, CachePolicyCatalog policyCatalog) {
        // Compatibility no-op for registrars generated before two-phase registration.
    }
}

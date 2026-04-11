package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.config.CacheDatabaseConfig;

@FunctionalInterface
public interface CacheDatabaseConfigCustomizer {

    void customize(CacheDatabaseConfig.Builder builder, CacheDbSpringProperties properties);
}

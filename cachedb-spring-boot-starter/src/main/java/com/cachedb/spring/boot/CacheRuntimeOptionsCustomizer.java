package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.config.CacheRuntimeOptions;

/** Independent from the stable base-config customizer; invoked after runtime property binding. */
@FunctionalInterface
public interface CacheRuntimeOptionsCustomizer {
    void customize(CacheRuntimeOptions.Builder builder, CacheDbSpringProperties properties);
}

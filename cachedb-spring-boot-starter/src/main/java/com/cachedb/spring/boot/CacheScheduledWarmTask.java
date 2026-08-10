package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheWarmPlan;

/** Typed, reflection-free adapter generated for an {@link CacheScheduledWarm} method. */
public interface CacheScheduledWarmTask {
    CacheScheduledWarmDescriptor descriptor();

    CacheWarmPlan createPlan();
}

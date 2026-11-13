package com.reactor.cachedb.spring.boot.cache;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/** Cache-name-only labels; no tenant, key, value or request-derived dimensions. */
public final class CacheDbCacheMetrics implements MeterBinder {
    private final CacheDbCacheManager manager;
    public CacheDbCacheMetrics(CacheDbCacheManager manager) { this.manager = manager; }
    @Override public void bindTo(MeterRegistry registry) {
        for (String name : manager.getCacheNames()) {
            CacheDbCache cache = manager.getCache(name);
            FunctionCounter.builder("cachedb.spring.cache.hits", cache, c -> c.statistics().hits()).tag("cache", name).register(registry);
            FunctionCounter.builder("cachedb.spring.cache.near.hits", cache, c -> c.statistics().nearHits()).tag("cache", name).register(registry);
            FunctionCounter.builder("cachedb.spring.cache.misses", cache, c -> c.statistics().misses()).tag("cache", name).register(registry);
            FunctionCounter.builder("cachedb.spring.cache.failures", cache, c -> c.statistics().failures()).tag("cache", name).register(registry);
            FunctionCounter.builder("cachedb.spring.cache.fenced.loads", cache, c -> c.statistics().fencedLoads()).tag("cache", name).register(registry);
            FunctionCounter.builder("cachedb.spring.cache.puts", cache, c -> c.statistics().puts()).tag("cache", name).register(registry);
            FunctionCounter.builder("cachedb.spring.cache.evictions", cache, c -> c.statistics().evictions()).tag("cache", name).register(registry);
            FunctionCounter.builder("cachedb.spring.cache.clears", cache, c -> c.statistics().clears()).tag("cache", name).register(registry);
        }
        FunctionCounter.builder("cachedb.spring.cache.late.fills.rejected", manager,
                m -> m.invalidationCoordinator().rejectedFills()).register(registry);
        FunctionCounter.builder("cachedb.spring.cache.notifications", manager,
                m -> m.invalidationCoordinator().receivedNotifications()).register(registry);
        FunctionCounter.builder("cachedb.spring.cache.disconnects", manager,
                m -> m.invalidationCoordinator().disconnectCount()).register(registry);
        Gauge.builder("cachedb.spring.cache.generation.scopes", manager,
                m -> m.invalidationCoordinator().generationScopes()).register(registry);
        FunctionCounter.builder("cachedb.spring.cache.rejected", manager, CacheDbCacheManager::rejectedOperations).register(registry);
        Gauge.builder("cachedb.spring.cache.inflight", manager, m -> m.flights().inFlightCount()).register(registry);
        Gauge.builder("cachedb.spring.cache.waiters", manager, m -> m.flights().waiterCount()).register(registry);
        Gauge.builder("cachedb.spring.cache.invalidation.connected", manager, m -> m.invalidationConnected() ? 1 : 0).register(registry);
    }
}

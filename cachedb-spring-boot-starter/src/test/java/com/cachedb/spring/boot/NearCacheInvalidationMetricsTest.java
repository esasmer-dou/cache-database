package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.core.config.NearCacheInvalidationMode;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.queue.WriteBehindWorkerSnapshot;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.CacheDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class NearCacheInvalidationMetricsTest {
    @Test void gaugesAndCountersAreLocalAndUseOnlyFiniteModeTags() {
        var operations = mock(RedisClientOperations.class);
        var registry = new SimpleMeterRegistry();
        try (var near = new RedisNearCacheCoordinator(operations, new RedisKeyStrategy("metrics"))) {
            var database = mock(CacheDatabase.class);
            when(database.nearCacheCoordinator()).thenReturn(near);
            when(database.redisReadMetrics()).thenReturn(new RedisRepositoryReadState.Metrics(
                    new RedisReadRuntime.Metrics(0, 0, 0, 0), 0, 0, 0));
            when(database.workerSnapshot()).thenReturn(mock(WriteBehindWorkerSnapshot.class));
            when(database.storagePerformanceCollector()).thenReturn(new StoragePerformanceCollector());
            new CacheDatabaseMetricsMeterBinder(database).bindTo(registry);
            assertEquals(0, registry.get("cachedb.nearcache.ready").gauge().value());
            assertEquals(0, registry.get("cachedb.nearcache.tracking.ready").gauge().value());
            assertEquals(1, registry.get("cachedb.nearcache.legacy.publish").gauge().value());
            for (var mode : NearCacheInvalidationMode.values())
                assertEquals(mode == NearCacheInvalidationMode.PUBSUB ? 1 : 0,
                        registry.get("cachedb.nearcache.invalidation.mode").tag("mode", mode.name()).gauge().value());
            assertEquals(0, registry.get("cachedb.nearcache.invalidations").functionCounter().count());
            long before = near.flushAllEvents(); near.topologyChanged();
            assertEquals(before + 1, registry.get("cachedb.nearcache.flushes").functionCounter().count());
            assertEquals(0, registry.get("cachedb.nearcache.reconnects").functionCounter().count());
            assertEquals(3, registry.find("cachedb.nearcache.invalidation.mode").gauges().size());
            verifyNoInteractions(operations);
            verify(database, never()).storagePerformanceSnapshot();
        } finally {
            registry.close();
        }
    }
}

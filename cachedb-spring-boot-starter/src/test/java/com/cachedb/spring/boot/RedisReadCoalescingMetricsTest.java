package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.core.config.NearCacheInvalidationMode;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.queue.WriteBehindWorkerSnapshot;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.starter.CacheDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RedisReadCoalescingMetricsTest {
    @Test void oneRuntimeSnapshotExposesTransportAndExclusivePolicyBypassReasons() {
        var database = mock(CacheDatabase.class);
        var near = mock(RedisNearCacheCoordinator.class);
        when(database.nearCacheCoordinator()).thenReturn(near);
        when(near.invalidationMode()).thenReturn(NearCacheInvalidationMode.SERVER_TRACKING);
        when(near.connected()).thenReturn(false);
        when(near.trackingReady()).thenReturn(false);
        when(near.publishLegacyInvalidations()).thenReturn(true);
        when(database.workerSnapshot()).thenReturn(mock(WriteBehindWorkerSnapshot.class));
        when(database.storagePerformanceCollector()).thenReturn(new StoragePerformanceCollector());
        var reads = new AtomicReference<>(new RedisRepositoryReadState.Metrics(
                new RedisReadRuntime.Metrics(7, 21, 3, 2), 3, 5, 7));
        when(database.redisReadMetrics()).thenAnswer(ignored -> reads.get());
        var registry = new SimpleMeterRegistry();
        try {
            var binder = new CacheDatabaseMetricsMeterBinder(database); binder.bindTo(registry);
            assertEquals(7, registry.get("cachedb.redis.read.coalescing.batches").functionCounter().count());
            assertEquals(21, registry.get("cachedb.redis.read.coalescing.coalesced.requests").functionCounter().count());
            assertEquals(17, registry.get("cachedb.redis.read.coalescing.bypassed").functionCounter().count());
            assertEquals(3, registry.get("cachedb.redis.read.coalescing.bypass.disabled").functionCounter().count());
            assertEquals(5, registry.get("cachedb.redis.read.coalescing.bypass.causal").functionCounter().count());
            assertEquals(7, registry.get("cachedb.redis.read.coalescing.bypass.unitofwork").functionCounter().count());
            assertEquals(3, registry.get("cachedb.redis.read.coalescing.average.batch.size").gauge().value());
            reads.set(new RedisRepositoryReadState.Metrics(new RedisReadRuntime.Metrics(9, 30, 30.0 / 9, 4), 4, 6, 8));
            assertEquals(9, registry.get("cachedb.redis.read.coalescing.batches").functionCounter().count());
            assertEquals(30, registry.get("cachedb.redis.read.coalescing.coalesced.requests").functionCounter().count());
            assertEquals(22, registry.get("cachedb.redis.read.coalescing.bypassed").functionCounter().count());
            assertEquals(30.0 / 9, registry.get("cachedb.redis.read.coalescing.average.batch.size").gauge().value());
            assertEquals(1, registry.get("cachedb.nearcache.invalidation.mode").tag("mode", "SERVER_TRACKING").gauge().value());
            assertEquals(0, registry.get("cachedb.nearcache.ready").gauge().value());
            assertEquals(0, registry.get("cachedb.nearcache.tracking.ready").gauge().value());
            assertEquals(1, registry.get("cachedb.nearcache.legacy.publish").gauge().value());
            binder.bindTo(registry);
            var meters = registry.getMeters().stream().filter(m -> m.getId().getName().startsWith("cachedb.redis.read.coalescing.")).toList();
            assertEquals(7, meters.size(), "Register per runtime, not per repository or scrape");
            assertTrue(meters.stream().allMatch(m -> m.getId().getTags().isEmpty()));
            verify(database, never()).storagePerformanceSnapshot();
        } finally {
            registry.close();
        }
    }
}

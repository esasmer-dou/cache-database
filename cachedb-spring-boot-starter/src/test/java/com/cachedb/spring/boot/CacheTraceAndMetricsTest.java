package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.queue.WriteBehindWorkerSnapshot;
import com.reactor.cachedb.core.trace.TraceContexts;
import com.reactor.cachedb.core.trace.TraceMetadataCodec;
import com.reactor.cachedb.starter.CacheDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CacheTraceAndMetricsTest {
    @Test void mdcBridgeCapturesOnlyAllowedFieldsAndRestoresNestedScopes() {
        var bridge = new MdcTraceContextPropagator();
        MDC.put("password", "never-forward");
        MDC.put("correlationId", "previous");
        try {
            try (var scope = bridge.restore(Map.of("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                    "correlationId", "request-17"))) {
                assertEquals("request-17", MDC.get("correlationId"));
                assertEquals("request-17", TraceContexts.current().get("correlationId"));
                assertFalse(bridge.capture().containsKey("password"));
                assertEquals(bridge.capture(), TraceMetadataCodec.decode(TraceMetadataCodec.encode(bridge.capture())));
            }
            assertEquals("previous", MDC.get("correlationId"));
            assertTrue(TraceContexts.current().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> TraceContexts.withMetadata(Map.of("traceparent", "x".repeat(513))));
        } finally { MDC.clear(); }
    }

    @Test void standardMetersReflectLocalCountersWithoutNetworkSnapshots() {
        var database = mock(CacheDatabase.class);
        var worker = mock(WriteBehindWorkerSnapshot.class);
        var collector = new StoragePerformanceCollector();
        when(database.workerSnapshot()).thenReturn(worker);
        when(database.storagePerformanceCollector()).thenReturn(collector);
        when(worker.flushedCount()).thenReturn(7L);
        when(worker.staleSkippedCount()).thenReturn(2L);
        var registry = new SimpleMeterRegistry();
        try {
            new CacheDatabaseMetricsMeterBinder(database).bindTo(registry);
            collector.recordRedisRead(1500);
            collector.recordRedisWrite(2000);
            collector.recordSqlWrite(3000);
            assertEquals(7, registry.get("cachedb.writebehind.flushed").functionCounter().count());
            assertEquals(2, registry.get("cachedb.writebehind.stale.skipped").functionCounter().count());
            assertEquals(1, registry.get("cachedb.redis.read.latency").functionTimer().count());
            assertEquals(1.5, registry.get("cachedb.redis.read.latency").functionTimer().totalTime(TimeUnit.MILLISECONDS));
            assertEquals(3, registry.get("cachedb.sql.flush.latency").functionTimer().totalTime(TimeUnit.MILLISECONDS));
            verify(database, never()).storagePerformanceSnapshot();
        } finally { registry.close(); }
    }
}

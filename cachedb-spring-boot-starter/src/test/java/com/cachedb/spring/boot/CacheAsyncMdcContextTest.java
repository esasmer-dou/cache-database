package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;

@Timeout(20)
class CacheAsyncMdcContextTest {
    @Test void configuredMdcPropagatorCapturesThenRestoresPooledWorkerOnSuccessAndFailure() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var prior = MDC.getCopyOfContextMap();
        var restored = new ArrayBlockingQueue<Boolean>(3);
        Executor borrowed = command -> pool.execute(() -> {
            MDC.put("correlationId", "worker");
            try {
                command.run();
                restored.add("worker".equals(MDC.get("correlationId")));
            } finally { MDC.clear(); }
        });
        var config = com.reactor.cachedb.core.config.CacheDatabaseConfig.builder()
                .traceContextPropagator(new MdcTraceContextPropagator()).build();
        try (var executor = new CacheAsyncExecutor(1, borrowed, config.traceContextPropagator())) {
            CompletableFuture<String> future;
            MDC.clear();
            MDC.put("correlationId", "request");
            try (var tenant = CacheTenantContext.withTenant("tenant");
                    var causal = CacheCausalContext.withMinVersion("rows", "id", 9)) {
                future = executor.submit(() -> {
                    assertEquals("tenant", CacheTenantContext.currentTenant().orElseThrow());
                    assertEquals(9, CacheCausalContext.requiredVersion("rows", "id"));
                    return MDC.get("correlationId");
                });
            }
            MDC.clear();
            assertEquals("request", future.get(5, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, restored.poll(5, TimeUnit.SECONDS));
            assertTrue(executor.awaitIdle(Duration.ofSeconds(5)));
            executor.submit(() -> {
                assertNull(MDC.get("correlationId"));
                throw new IllegalArgumentException("expected");
            }).handle((value, failure) -> { assertNotNull(failure); return null; }).get(5, TimeUnit.SECONDS);
            assertEquals(Boolean.TRUE, restored.poll(5, TimeUnit.SECONDS));
            assertTrue(executor.awaitIdle(Duration.ofSeconds(5)));
            assertNull(MDC.get("correlationId"));
        } finally {
            MDC.clear();
            if (prior != null) MDC.setContextMap(prior);
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

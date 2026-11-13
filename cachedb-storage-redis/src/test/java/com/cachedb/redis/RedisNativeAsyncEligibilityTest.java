package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(40)
class RedisNativeAsyncEligibilityTest {
    @Test void disabledRawBridgeHandlesThousandMissesWithoutSubmittingToThrowingExecutor() throws Exception {
        var client = new RedisReadFakeClient();
        client.hold(16);
        var config = RedisReadCoalescingConfig.builder().enabled(false)
                .maxInFlightBatches(16).timeoutMillis(30_000).build();
        try (var gate = new CacheAsyncExecutor(1024, task -> { throw new AssertionError("No worker per raw miss"); },
                TraceContexts.threadLocal()); var state = new RedisRepositoryReadState(client, config)) {
            try {
                var results = new ArrayList<CompletableFuture<String>>(1000);
                for (int i = 0; i < 1000; i++) {
                    String entity = "e" + i, tombstone = "t" + i;
                    assertTrue(state.useAsyncTransport(0), "The disabled sync switch must not disable native async FIFO");
                    results.add(gate.submitExternal(() -> {
                        var raw = state.runtime().readAsync(entity, tombstone, false);
                        return new CacheAsyncExecutor.ExternalOperation<>(raw, state.runtime().physicalCompletion(raw));
                    }, RedisPointRead::payload));
                }
                assertTrue(client.started.await(3, TimeUnit.SECONDS));
                assertEquals(16, client.calls.get());
                assertEquals(0, gate.snapshot().outstandingDispatches());
                assertEquals(24, gate.snapshot().availablePermits());
                client.release();
                for (var result : results) assertNull(result.get(10, TimeUnit.SECONDS));
                assertTrue(gate.awaitIdle(java.time.Duration.ofSeconds(2)));
                assertEquals(1000, client.calls.get());
                assertTrue(client.peak.get() <= 16);
                assertEquals(0, state.metrics().disabled(), "Do not count the sync disabled bypass as well");
                assertEquals(1000, state.metrics().transport().bypassed());
                assertEquals(0, state.metrics().transport().coalescedRequests());
            } finally { client.release(); }
        }
    }

    @Test void causalEligibilityProbeDoesNotDuplicateTheLegacyDelegateBypassMetric() {
        var client = new RedisReadFakeClient();
        try (var state = new RedisRepositoryReadState(client,
                RedisReadCoalescingConfig.builder().enabled(true).build())) {
            assertFalse(state.useAsyncTransport(7));
            assertEquals(0, state.metrics().causal());
            assertFalse(state.coalesce(7)); // The bounded offloading adapter calls the full synchronous delegate.
            assertEquals(1, state.metrics().causal());
            assertEquals(0, client.calls.get());
        }
    }

    @Test void activeUnitOfWorkIsRejectedOnItsCallerAndNeverTransferredToRawIo() {
        var client = new RedisReadFakeClient();
        CacheWriteBatch batch = new CacheWriteBatch() {
            public <T> CacheWriteBatch put(T entity) { throw new AssertionError(); }
            public <T> CacheWriteBatch put(T entity, long version) { throw new AssertionError(); }
            public <T, ID> CacheWriteBatch delete(Class<T> type, ID id) { throw new AssertionError(); }
            public CacheWriteBatchReceipt commit() { throw new AssertionError(); }
        };
        try (var state = new RedisRepositoryReadState(client, RedisReadCoalescingConfig.defaults());
                var work = CacheUnitOfWork.begin(this, batch)) {
            assertThrows(IllegalStateException.class, () -> state.useAsyncTransport(0));
            assertThrows(IllegalStateException.class, () -> state.useAsyncTransport(5));
            assertTrue(CacheUnitOfWork.active());
            assertEquals(0, client.calls.get());
        }
    }

    @Test void asyncEligibilityCannotEscapeClosedRuntimeAndRejectsInvalidMinimumVersion() {
        var state = new RedisRepositoryReadState(new RedisReadFakeClient(), RedisReadCoalescingConfig.defaults());
        assertThrows(IllegalArgumentException.class, () -> state.useAsyncTransport(-1));
        state.close();
        assertThrows(RejectedExecutionException.class, () -> state.useAsyncTransport(0));
        assertThrows(RejectedExecutionException.class, () -> state.useAsyncTransport(5));
    }
}

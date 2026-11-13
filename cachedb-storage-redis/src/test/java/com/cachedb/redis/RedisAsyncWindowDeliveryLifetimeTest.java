package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RedisAsyncWindowDeliveryLifetimeTest {
    @Test void prepareRegistersWithoutIoAndCancellationBeforeActivateNeverLoadsARow() throws Exception {
        try (var gate = new CacheAsyncExecutor(1, ignored -> { throw new AssertionError(); }, TraceContexts.threadLocal())) {
            var calls = new AtomicInteger();
            var window = new RedisAsyncReadWindow<String, Integer>(List.of(1), gate, id -> {
                calls.incrementAndGet(); throw new AssertionError("No enqueue after pre-start cancellation");
            });
            var operation = window.prepare();
            assertEquals(0, calls.get());
            operation.result().cancel(false);
            window.activate();
            window.deliveryCompletion().get(2, TimeUnit.SECONDS);
            assertEquals(0, calls.get());
            assertTrue(operation.physical().isDone());
        }
    }

    @Test void windowDeliveryLifetimeOutlivesItsPhysicalStageAndBlockedDataPublication() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var gate = new CacheAsyncExecutor(1, ignored -> { throw new AssertionError(); }, TraceContexts.threadLocal())) {
            try {
                var row = new CompletableFuture<RedisAsyncReadWindow.Row<String>>();
                var io = new CompletableFuture<Void>();
                var window = new RedisAsyncReadWindow<String, Integer>(List.of(1), gate,
                        id -> new CacheAsyncExecutor.ExternalOperation<>(row, io));
                var operation = window.prepare();
                var callback = operation.result().thenRun(() -> {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                });
                window.activate();
                io.complete(null);
                Thread.ofVirtual().start(() -> row.complete(new RedisAsyncReadWindow.Row<>("row", 3, true)));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(operation.physical().isDone());
                assertFalse(window.deliveryCompletion().isDone());
                release.countDown();
                callback.get(2, TimeUnit.SECONDS);
                window.deliveryCompletion().get(2, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }
}

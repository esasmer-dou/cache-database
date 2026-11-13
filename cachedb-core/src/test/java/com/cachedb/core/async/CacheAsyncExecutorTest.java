package com.reactor.cachedb.core.async;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class CacheAsyncExecutorTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test void ownedWorkersDoNotInheritUnrelatedCallerAuthority() throws Exception {
        var ambient = new InheritableThreadLocal<String>();
        ambient.set("caller-only");
        try (var executor = new CacheAsyncExecutor()) {
            assertNull(executor.submit(ambient::get).get(5, TimeUnit.SECONDS));
            assertEquals("caller-only", ambient.get());
        } finally { ambient.remove(); }
    }

    @Test void defaultExecutorUsesVirtualThreadsAndHasExplicitIdempotentLifecycle() throws Exception {
        var executor = new CacheAsyncExecutor();
        assertEquals(1024, executor.snapshot().maxConcurrent());
        assertTrue(executor.submit(() -> Thread.currentThread().isVirtual()).get(5, TimeUnit.SECONDS));
        assertTrue(executor.awaitIdle(WAIT));
        executor.close();
        executor.close();
        assertRejected(executor.submit(() -> fail("closed")), CacheAsyncRejectedException.Reason.CLOSED);
        assertThrows(IllegalArgumentException.class, () -> new CacheAsyncExecutor(0, TraceContexts.threadLocal()));
        assertThrows(IllegalArgumentException.class, () -> new CacheAsyncExecutor(65_537, TraceContexts.threadLocal()));
        assertThrows(IllegalArgumentException.class, () ->
                new CacheAsyncExecutor(1, null, TraceContexts.threadLocal(), Duration.ofSeconds(31)));
    }

    @Test void cancellationDuringUninterruptibleIoCannotOversubscribeEvenInAStorm() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(4);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        try (var executor = new CacheAsyncExecutor(4, TraceContexts.threadLocal())) {
            var futures = new ArrayList<CompletableFuture<Integer>>();
            try {
                for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                    int current = active.incrementAndGet();
                    maximum.accumulateAndGet(current, Math::max);
                    started.countDown();
                    try {
                        boolean done = false;
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (!done && System.nanoTime() < deadline) {
                            try { done = release.await(100, TimeUnit.MILLISECONDS); }
                            catch (InterruptedException ignored) { /* Simulate an interrupt-ignoring driver. */ }
                        }
                        if (!done) throw new TimeoutException("fixture release missing");
                        return current;
                    } finally { active.decrementAndGet(); }
                }));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                futures.forEach(future -> future.cancel(true));
                assertEquals(0, executor.snapshot().availablePermits());
                for (int i = 0; i < 1000; i++)
                    assertRejected(executor.submit(() -> fail("canceled I/O is still running")),
                            CacheAsyncRejectedException.Reason.SATURATED);
                assertEquals(4, active.get());
                assertEquals(4, maximum.get());
            } finally { release.countDown(); }
            assertTrue(executor.awaitIdle(WAIT));
            assertEquals(4, executor.snapshot().availablePermits());
            assertEquals(0, active.get());
            assertEquals(4, executor.snapshot().cancelled());
            assertEquals(1, executor.submit(() -> 1).get(5, TimeUnit.SECONDS));
        }
    }

    @Test void preStartCancellationReleasesPermitButDoesNotGrowBorrowedQueue() throws Exception {
        var queue = new ArrayBlockingQueue<Runnable>(1);
        Executor borrowed = command -> {
            if (!queue.offer(command)) throw new RejectedExecutionException("fixture queue full");
        };
        var invoked = new AtomicInteger();
        try (var executor = new CacheAsyncExecutor(1, borrowed, TraceContexts.threadLocal())) {
            for (int i = 0; i < 1000; i++) {
                var future = executor.submit(invoked::incrementAndGet);
                assertTrue(future.cancel(true));
                assertTrue(future.cancel(true));
                assertEquals(1, executor.snapshot().availablePermits());
                assertEquals(1, executor.snapshot().outstandingDispatches());
                assertRejected(executor.submit(invoked::incrementAndGet), CacheAsyncRejectedException.Reason.SATURATED);
                assertEquals(1, queue.size());
                queue.remove().run();
                assertEquals(0, executor.snapshot().outstandingDispatches());
            }
            assertEquals(0, invoked.get());
            assertEquals(1000, executor.snapshot().cancelled());
            assertTrue(executor.awaitIdle(WAIT));
        }
    }

    @Test void rejectionInlineExecutionAndCaptureFailureReleaseBothReservations() throws Exception {
        try (var rejected = new CacheAsyncExecutor(1, command -> { throw new RejectedExecutionException(); },
                TraceContexts.threadLocal());
                var inline = new CacheAsyncExecutor(1, Runnable::run, TraceContexts.threadLocal())) {
            assertRejected(rejected.submit(() -> fail("rejected")), CacheAsyncRejectedException.Reason.EXECUTOR_REJECTED);
            assertRejected(inline.submit(() -> fail("caller must not run I/O")), CacheAsyncRejectedException.Reason.INLINE_EXECUTOR);
            assertEquals(1, rejected.snapshot().availablePermits());
            assertEquals(0, rejected.snapshot().outstandingDispatches());
            assertEquals(1, inline.snapshot().availablePermits());
            assertEquals(0, inline.snapshot().outstandingDispatches());
        }
        var broken = new com.reactor.cachedb.core.trace.TraceContextPropagator() {
            public Map<String, String> capture() { throw new IllegalArgumentException("bad trace"); }
            public Scope restore(Map<String, String> value) { throw new AssertionError(); }
        };
        try (var executor = new CacheAsyncExecutor(1, broken)) {
            var failure = assertThrows(CompletionException.class, () -> executor.submit(() -> 1).join());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause().getCause());
            assertEquals(1, executor.snapshot().availablePermits());
            assertEquals(0, executor.snapshot().outstandingDispatches());
        }
    }

    @Test void capturesAtSubmissionMasksEmptyContextAndRestoresPoisonedPooledWorker() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var restored = new ArrayBlockingQueue<Boolean>(3);
        Executor borrowed = command -> pool.execute(() -> {
            try (var tenant = CacheTenantContext.withTenant("worker");
                    var causal = CacheCausalContext.withMinVersion("rows", "id", 3);
                    var trace = TraceContexts.withMetadata(Map.of("traceId", "worker"))) {
                command.run();
                restored.add(CacheTenantContext.currentTenant().orElseThrow().equals("worker")
                        && CacheCausalContext.requiredVersion("rows", "id") == 3
                        && CacheCausalContext.requiredVersion("leaked", "id") == 0
                        && TraceContexts.current().equals(Map.of("traceId", "worker")));
            }
        });
        try (var executor = new CacheAsyncExecutor(1, borrowed, TraceContexts.threadLocal())) {
            CompletableFuture<String> captured;
            try (var tenant = CacheTenantContext.withTenant("request");
                    var causal = CacheCausalContext.withMinVersion("rows", "id", 7);
                    var trace = TraceContexts.withMetadata(Map.of("traceId", "request"))) {
                captured = executor.submit(() -> {
                    assertEquals(7, CacheCausalContext.requiredVersion("rows", "id"));
                    assertEquals(Map.of("traceId", "request"), TraceContexts.current());
                    return CacheTenantContext.currentTenant().orElseThrow();
                });
            }
            assertEquals("request", captured.get(5, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, restored.poll(5, TimeUnit.SECONDS));
            assertTrue(executor.awaitIdle(WAIT));
            executor.submit(() -> {
                assertTrue(CacheTenantContext.currentTenant().isEmpty());
                assertFalse(CacheTenantContext.isAdministrative());
                assertEquals("", CacheCausalContext.encodeToken());
                assertEquals(Map.of(), TraceContexts.current());
                CacheTenantContext.withTenant("leaked");
                CacheCausalContext.withMinVersion("leaked", "id", 1);
                throw new IllegalArgumentException("expected");
            }).handle((value, failure) -> { assertNotNull(failure); return null; }).get(5, TimeUnit.SECONDS);
            assertEquals(Boolean.TRUE, restored.poll(5, TimeUnit.SECONDS));
            assertTrue(executor.awaitIdle(WAIT));
            assertTrue(CacheTenantContext.currentTenant().isEmpty());
            assertEquals("", CacheCausalContext.encodeToken());
            assertEquals(Map.of(), TraceContexts.current());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void externallyCompletingFutureDoesNotReleaseRunningIoPermit() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var executor = new CacheAsyncExecutor(1, TraceContexts.threadLocal())) {
            try {
                var future = executor.submit(() -> {
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return 1;
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertTrue(future.complete(99));
                assertEquals(0, executor.snapshot().availablePermits());
                assertRejected(executor.submit(() -> 2), CacheAsyncRejectedException.Reason.SATURATED);
            } finally { release.countDown(); }
            assertTrue(executor.awaitIdle(WAIT));
            assertEquals(1, executor.snapshot().availablePermits());
        }
    }

    @Test void completionAllowsChainingWithoutFalseSaturation() throws Exception {
        var release = new CountDownLatch(1);
        try (var executor = new CacheAsyncExecutor(1, TraceContexts.threadLocal())) {
            var first = executor.submit(() -> {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return 1;
            });
            var chain = first.thenCompose(value -> executor.submit(() -> value + 1));
            release.countDown();
            assertEquals(2, chain.get(5, TimeUnit.SECONDS));
            assertTrue(executor.awaitIdle(WAIT));
            assertEquals(1, executor.snapshot().availablePermits());
        } finally { release.countDown(); }
    }

    @Test void startCancelRaceReclaimsEachReservationExactlyOnce() throws Exception {
        var queue = new ArrayBlockingQueue<Runnable>(1);
        var runner = Executors.newSingleThreadExecutor();
        try (var executor = new CacheAsyncExecutor(1, queue::add, TraceContexts.threadLocal())) {
            for (int i = 0; i < 500; i++) {
                var future = executor.submit(() -> 1);
                var invocation = runner.submit(queue.remove());
                future.cancel(true);
                invocation.get(5, TimeUnit.SECONDS);
                assertTrue(executor.awaitIdle(WAIT));
                assertEquals(1, executor.snapshot().availablePermits());
                assertEquals(0, executor.snapshot().outstandingDispatches());
            }
        } finally {
            runner.shutdownNow();
            assertTrue(runner.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void closeTimeoutDoesNotReturnRunningIoPermitOrShutDownBorrowedExecutor() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var executor = new CacheAsyncExecutor(1, pool, TraceContexts.threadLocal(), Duration.ofMillis(10));
        try {
            executor.submit(() -> {
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return 1;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, executor::close);
            assertEquals(0, executor.snapshot().availablePermits());
            assertFalse(pool.isShutdown());
            assertRejected(executor.submit(() -> 2), CacheAsyncRejectedException.Reason.CLOSED);
        } finally {
            release.countDown();
            assertTrue(executor.awaitIdle(WAIT));
            executor.close();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void assertRejected(CompletableFuture<?> future, CacheAsyncRejectedException.Reason reason) {
        var failure = assertThrows(CompletionException.class, future::join);
        assertEquals(reason, assertInstanceOf(CacheAsyncRejectedException.class, failure.getCause()).reason());
    }
}

package com.reactor.cachedb.core.async;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CacheAsyncDeliveryLifetimeTest {
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }

    @Test void runningWorkerCancellationDoesNotCompleteItsTrackedProducerLifetime() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var gate = new CacheAsyncExecutor(1, TraceContexts.threadLocal())) {
            try {
                var operation = gate.submitTracked(() -> { entered.countDown(); await(release); return "row"; });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(operation.result().cancel(true));
                assertTrue(operation.result().cancel(false));
                assertFalse(operation.physical().isDone());
                assertEquals(0, gate.snapshot().availablePermits());
                release.countDown();
                operation.physical().get(2, TimeUnit.SECONDS);
                assertEquals(1, gate.snapshot().availablePermits());
            } finally { release.countDown(); }
        }
    }

    @Test void trackedWorkerHoldsPublicationLifetimeButNotIoPermitThroughBlockedCallbacks() throws Exception {
        var start = new CountDownLatch(1); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var gate = new CacheAsyncExecutor(1, TraceContexts.threadLocal())) {
            try {
                var operation = gate.submitTracked(() -> { await(start); return "row"; });
                var callback = operation.result().thenRun(() -> { entered.countDown(); await(release); });
                start.countDown();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(1, gate.snapshot().availablePermits());
                assertFalse(operation.physical().isDone(), "Publication lifetime is not just socket exit");
                release.countDown();
                callback.get(2, TimeUnit.SECONDS);
                operation.physical().get(2, TimeUnit.SECONDS);
            } finally { start.countDown(); release.countDown(); }
        }
    }

    @Test void externalLifetimeWaitsForBlockedPublicCallbackAfterPhysicalStageWasReleased() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var gate = new CacheAsyncExecutor(1, ignored -> { throw new AssertionError("No worker"); }, TraceContexts.threadLocal())) {
            try {
                var data = new CompletableFuture<String>(); var io = new CompletableFuture<Void>();
                var result = gate.submitExternal(() -> new CacheAsyncExecutor.ExternalOperation<>(data, io), value -> value);
                var lifetime = gate.deliveryCompletion(result);
                var callback = result.thenRun(() -> { entered.countDown(); await(release); });
                io.complete(null);
                Thread.ofVirtual().start(() -> data.complete("row"));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(1, gate.snapshot().availablePermits());
                assertFalse(lifetime.isDone());
                release.countDown();
                callback.get(2, TimeUnit.SECONDS);
                lifetime.get(2, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }

    @Test void queuedWorkerCancellationKeepsBorrowedDispatchStubButCompletesHonestNoIoLifetime() {
        try (var gate = new CacheAsyncExecutor(1, ignored -> {}, TraceContexts.threadLocal())) {
            var operation = gate.submitTracked(() -> { throw new AssertionError(); });
            assertTrue(operation.result().cancel(true));
            assertTrue(operation.physical().isDone());
            assertEquals(1, gate.snapshot().availablePermits());
            assertEquals(1, gate.snapshot().outstandingDispatches());
        }
    }

    @Test void trackedDispatchErrorAfterWorkerStartCannotPretendThatNoProducerExists() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var dispatchFailure = new AssertionError("Executor threw after handing off");
        try (var gate = new CacheAsyncExecutor(1, task -> {
            Thread.ofVirtual().start(task); await(entered); throw dispatchFailure;
        }, TraceContexts.threadLocal())) {
            try {
                var operation = gate.submitTracked(() -> { entered.countDown(); await(release); return "late"; });
                assertSame(dispatchFailure,
                        assertThrows(ExecutionException.class, () -> operation.result().get(2, TimeUnit.SECONDS)).getCause());
                assertFalse(operation.physical().isDone());
                assertEquals(0, gate.snapshot().availablePermits());
                release.countDown();
                operation.physical().get(2, TimeUnit.SECONDS);
                assertEquals(1, gate.snapshot().availablePermits());
            } finally { release.countDown(); }
        }
    }

    @Test void stopAcceptingDuringContextCapturePreventsAnyExternalTransportStart() throws Exception {
        var captured = new CountDownLatch(1); var release = new CountDownLatch(1);
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        var trace = new com.reactor.cachedb.core.trace.TraceContextPropagator() {
            public java.util.Map<String, String> capture() { captured.countDown(); await(release); return java.util.Map.of(); }
            public Scope restore(java.util.Map<String, String> metadata) { return () -> {}; }
        };
        try (var gate = new CacheAsyncExecutor(1, ignored -> { throw new AssertionError(); }, trace)) {
            try {
                var returned = new CompletableFuture<CompletableFuture<String>>();
                Thread.ofVirtual().start(() -> returned.complete(gate.submitExternal(() -> {
                    starts.incrementAndGet();
                    return new CacheAsyncExecutor.ExternalOperation<>(
                            CompletableFuture.completedFuture("wrong"), CompletableFuture.completedFuture(null));
                }, value -> value)));
                assertTrue(captured.await(2, TimeUnit.SECONDS));
                gate.stopAccepting();
                release.countDown();
                var failure = assertInstanceOf(CacheAsyncRejectedException.class,
                        assertThrows(ExecutionException.class,
                                () -> returned.get(2, TimeUnit.SECONDS).get(2, TimeUnit.SECONDS)).getCause());
                assertEquals(CacheAsyncRejectedException.Reason.CLOSED, failure.reason());
                assertEquals(0, starts.get());
                assertEquals(1, gate.snapshot().availablePermits());
            } finally { release.countDown(); }
        }
    }
}

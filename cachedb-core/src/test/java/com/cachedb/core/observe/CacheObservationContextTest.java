package com.reactor.cachedb.core.observe;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CacheObservationContextTest {
    @Test void noopUsesSingletonScopesBindingsAndUnchangedDelegate() {
        var noop = NoOpCacheObservationRecorder.INSTANCE;
        assertFalse(noop.enabled());
        for (int i = 0; i < 10_000; i++) {
            assertSame(NoOpCacheObservationRecorder.SCOPE, noop.start(CacheOperation.FIND_BY_ID));
            assertSame(NoOpCacheObservationRecorder.BINDING, CacheObservationContext.attach(NoOpCacheObservationRecorder.SCOPE));
        }
    }
    @Test void nestedBindingsAndEmptyCaptureRestoreWithoutLeaking() throws Exception {
        var recorder = new RecordingCacheObservationRecorder();
        var outer = recorder.start(CacheOperation.QUERY);
        var inner = recorder.start(CacheOperation.FIND_BY_ID);
        var empty = CacheObservationContext.capture();
        try (var a = CacheObservationContext.attach(outer)) {
            try (var b = CacheObservationContext.attach(inner)) { CacheObservationContext.tier(Tier.L1); }
            assertSame(outer, CacheObservationContext.current());
            try (var masked = empty.restore()) {
                assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
            }
            var snapshot = CacheObservationContext.capture();
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> {
                    try (var restored = snapshot.restore()) { CacheObservationContext.tier(Tier.SOURCE); }
                    assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
                }).get(5, TimeUnit.SECONDS);
            }
        }
        assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
        assertEquals(Tier.SOURCE, ((RecordingCacheObservationRecorder.RecordedScope) outer).tier);
        assertFalse(((RecordingCacheObservationRecorder.RecordedScope) outer).closed.get());
    }
    @Test void reverseOrderAndThreadOwnershipAreEnforcedWithoutDestroyingTheContext() throws Exception {
        var recorder = new RecordingCacheObservationRecorder();
        var first = CacheObservationContext.attach(recorder.start(CacheOperation.QUERY));
        var second = CacheObservationContext.attach(recorder.start(CacheOperation.COUNT));
        assertThrows(IllegalStateException.class, first::close);
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> assertThrows(IllegalStateException.class, second::close)).get(5, TimeUnit.SECONDS);
        } finally { second.close(); first.close(); }
        assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
    }
    @Test void nestingBudgetFailsBeforeReplacingTheCurrentBinding() {
        var recorder = new RecordingCacheObservationRecorder();
        var bindings = new java.util.ArrayList<CacheObservationRecorder.Binding>();
        try {
            for (int i = 0; i < 64; i++) bindings.add(CacheObservationContext.attach(recorder.start(CacheOperation.QUERY)));
            var current = CacheObservationContext.current();
            assertThrows(IllegalStateException.class, () -> CacheObservationContext.attach(recorder.start(CacheOperation.COUNT)));
            assertSame(current, CacheObservationContext.current());
        } finally {
            for (int i = bindings.size() - 1; i >= 0; i--) bindings.get(i).close();
        }
        assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
    }

    @Test void asyncCompletionFailureCancellationAndSubmissionErrorsCloseExactlyOnce() {
        var recorder = new RecordingCacheObservationRecorder();
        var original = new CompletableFuture<String>();
        var result = CacheObservations.callAsync(recorder, CacheOperation.FIND_BY_ID, "Order", () -> original);
        assertSame(original, result);
        assertFalse(recorder.last().closed.get());
        assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
        original.complete("row");
        assertEquals(1, recorder.last().closes.get());
        assertEquals(Outcome.HIT, recorder.last().outcome);
        var failure = new IllegalStateException("original");
        var failed = new CompletableFuture<String>();
        CacheObservations.callAsync(recorder, CacheOperation.SAVE, "Order", () -> failed);
        failed.completeExceptionally(new CompletionException(failure));
        assertSame(failure, recorder.last().failure);
        assertEquals(1, recorder.last().closes.get());
        var cancelled = new CompletableFuture<String>();
        CacheObservations.callAsync(recorder, CacheOperation.QUERY, "Order", () -> cancelled);
        assertTrue(cancelled.cancel(true));
        assertInstanceOf(CancellationException.class, recorder.last().failure);
        assertEquals(1, recorder.last().closes.get());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> CacheObservations.callAsync(recorder, CacheOperation.SAVE, "Order", () -> { throw failure; })));
        assertEquals(1, recorder.last().closes.get());
        assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
    }

    @Test void originalFutureMayCompleteBeforeObservationStopWithoutChangingIdentity() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        var restored = new java.util.concurrent.atomic.AtomicBoolean();
        CacheObservationRecorder recorder = operation -> new CacheObservationRecorder.Scope() {
            public void tier(Tier value) {}
            public void outcome(Outcome value) {}
            public void error(Throwable failure) {}
            public void close() {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                closes.incrementAndGet();
            }
        };
        var original = new CompletableFuture<String>();
        var result = CacheObservations.callAsync(recorder, CacheOperation.FIND_BY_ID, "Order", () -> original);
        assertSame(original, result);
        var completer = Thread.ofVirtual().start(() -> {
            original.complete("row");
            restored.set(CacheObservationContext.current() == NoOpCacheObservationRecorder.SCOPE);
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("row", result.get(5, TimeUnit.SECONDS));
            assertEquals(0, closes.get(), "Future completion is not an observer-closure barrier");
        } finally {
            release.countDown();
            completer.join(5000);
        }
        assertFalse(completer.isAlive());
        assertEquals(1, closes.get());
        assertTrue(restored.get());
        assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
    }
}

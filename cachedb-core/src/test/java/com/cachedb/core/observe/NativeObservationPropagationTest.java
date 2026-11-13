package com.reactor.cachedb.core.observe;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.async.CacheAsyncContext;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class NativeObservationPropagationTest {
    @Test void workerRestoresExactOwnerAndAllAmbientScopesAfterFailure() throws Exception {
        var recorder = new RecordingCacheObservationRecorder();
        Object owner = new Object(), other = new Object();
        var operation = recorder.start(CacheOperation.FIND_BY_ID);
        Runnable captured;
        try (var binding = CacheObservationContext.attach(operation, owner);
                var trace = TraceContexts.withMetadata(java.util.Map.of("correlationId", "preserved"))) {
            captured = CacheAsyncContext.wrap(TraceContexts.threadLocal(), () -> {
                assertTrue(CacheObservationContext.isOwnedBy(owner));
                assertFalse(CacheObservationContext.isOwnedBy(other));
                assertEquals("preserved", TraceContexts.current().get("correlationId"));
                CacheObservationContext.tier(Tier.REDIS);
                throw new IllegalStateException("business");
            });
        }
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                var ambient = recorder.start(CacheOperation.QUERY);
                try (var binding = CacheObservationContext.attach(ambient, other)) {
                    assertThrows(IllegalStateException.class, captured::run);
                    assertTrue(CacheObservationContext.isOwnedBy(other));
                    assertSame(ambient, CacheObservationContext.current());
                    assertTrue(TraceContexts.current().isEmpty());
                }
                assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
            }).get(5, TimeUnit.SECONDS);
        }
        assertEquals(Tier.REDIS, ((RecordingCacheObservationRecorder.RecordedScope) operation).tier);
        assertFalse(((RecordingCacheObservationRecorder.RecordedScope) operation).closed.get());
    }

    @Test void asyncOwnerLivesUntilCompletionAndNeverLeaksIntoCompletionWorker() throws Exception {
        var recorder = new RecordingCacheObservationRecorder();
        Object owner = new Object();
        var original = new CompletableFuture<String>();
        var task = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        assertSame(original, CacheObservations.callAsync(recorder, CacheOperation.QUERY, "Order", owner, () -> {
            task.set(CacheAsyncContext.wrap(TraceContexts.threadLocal(), () -> {
                assertTrue(CacheObservationContext.isOwnedBy(owner));
                CacheObservationContext.tier(Tier.SOURCE);
            }));
            return original;
        }));
        var operation = recorder.last();
        assertFalse(operation.closed.get());
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                task.get().run();
                assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
                original.complete("row");
                assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
            }).get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, operation.closes.get());
        assertEquals(Tier.SOURCE, operation.tier);
        assertFalse(CacheObservationContext.isOwnedBy(owner));
    }

    @Test void emptyCaptureMasksAnUnrelatedWorkerOwner() {
        Runnable empty = CacheAsyncContext.wrap(TraceContexts.threadLocal(),
                () -> assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current()));
        Object owner = new Object();
        try (var scope = CacheObservationContext.attach(NoOpCacheObservationRecorder.SCOPE, owner)) {
            empty.run();
            assertTrue(CacheObservationContext.isOwnedBy(owner));
        }
    }

    @Test void nativeHeaderCapacityNeverDropsExistingFieldsOrRejectsTheCapture() {
        var metadata = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < 8; i++) metadata.put("key" + i, "value");
        try (var scope = TraceContexts.withMetadata(metadata)) {
            var propagator = new ObservingTraceContextPropagator(TraceContexts.threadLocal(),
                    () -> "00-0123456789abcdef0123456789abcdef-0123456789abcdef-00");
            assertEquals(metadata, propagator.capture());
            assertEquals(1, propagator.invalidHeaders());
            assertEquals(metadata, TraceContexts.current());
        }
    }

    @Test void failedNativeBindingsDoNotChangeResultOrLeakOwner() {
        Object owner = new Object();
        CacheObservationRecorder recorder = operation -> new CacheObservationRecorder.Scope() {
            public void tier(Tier value) {}
            public void outcome(Outcome value) {}
            public void error(Throwable failure) {}
            public void close() {}
            public CacheObservationRecorder.Binding makeCurrent() { throw new IllegalStateException("telemetry"); }
        };
        assertEquals("row", CacheObservations.call(recorder, CacheOperation.FIND_BY_ID, "Order", owner, () -> {
            assertTrue(CacheObservationContext.isOwnedBy(owner)); return "row";
        }));
        assertFalse(CacheObservationContext.isOwnedBy(owner));
    }
}

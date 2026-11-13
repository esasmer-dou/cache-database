package com.reactor.cachedb.spring.boot.observe;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.observe.*;
import io.micrometer.observation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MicrometerCacheObservationRecorderTest {
    static final class Sink implements ObservationHandler<Observation.Context> {
        final List<CacheMicrometerObservationContext> started = new CopyOnWriteArrayList<>();
        final List<CacheMicrometerObservationContext> stopped = new CopyOnWriteArrayList<>();
        final List<List<CacheSpanLink>> originsAtStart = new CopyOnWriteArrayList<>();
        public boolean supportsContext(Observation.Context context) { return context instanceof CacheMicrometerObservationContext; }
        public void onStart(Observation.Context context) {
            var cache = (CacheMicrometerObservationContext) context;
            started.add(cache); originsAtStart.add(List.copyOf(cache.originLinks()));
        }
        public void onStop(Observation.Context context) { stopped.add((CacheMicrometerObservationContext) context); }
        CacheMicrometerObservationContext last() { return stopped.get(stopped.size() - 1); }
    }
    static Map<String, String> tags(Observation.Context context) {
        var tags = new LinkedHashMap<String, String>();
        for (var tag : context.getLowCardinalityKeyValues()) tags.put(tag.getKey(), tag.getValue());
        return tags;
    }
    static MicrometerCacheObservationRecorder recorder(ObservationRegistry registry) {
        return new MicrometerCacheObservationRecorder(registry,
                new CacheObservationTagPolicy(Set.of("Order"), Set.of("latest_orders")));
    }

    @Test void actualRegistryRecordsL1RedisSourceErrorAndOneAsyncLifetime() throws Exception {
        var registry = ObservationRegistry.create();
        var sink = new Sink(); registry.observationConfig().observationHandler(sink);
        var recorder = recorder(registry);
        for (Tier tier : Tier.values()) {
            assertEquals(Optional.of("row"), CacheObservations.call(recorder, CacheOperation.FIND_BY_ID, "Order", () -> {
                CacheObservationContext.tier(tier); return Optional.of("row");
            }));
            assertEquals(tier.name(), tags(sink.last()).get("cachedb.tier"));
            assertEquals("hit", tags(sink.last()).get("cachedb.outcome"));
            assertNull(registry.getCurrentObservation());
        }
        var business = new IllegalStateException("id=private payload=private tenant=private");
        assertSame(business, assertThrows(IllegalStateException.class, () -> CacheObservations.call(
                recorder, CacheOperation.QUERY, "Order", () -> { throw business; })));
        assertEquals("error", tags(sink.last()).get("cachedb.outcome"));
        assertEquals("CacheDB operation failed", sink.last().getError().getMessage());
        assertNull(sink.last().getError().getCause());
        assertNotSame(business, sink.last().getError());

        var pending = new CompletableFuture<Optional<String>>();
        var captured = new AtomicReference<CacheObservationContext.Snapshot>();
        var returned = CacheObservations.callAsync(recorder, CacheOperation.FIND_BY_ID, "Order", () -> {
            captured.set(CacheObservationContext.capture()); return pending;
        });
        assertSame(pending, returned);
        assertEquals(5, sink.started.size()); assertEquals(4, sink.stopped.size());
        assertNull(registry.getCurrentObservation());
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                try (var restored = captured.get().restore()) {
                    assertNotNull(registry.getCurrentObservation());
                    CacheObservationContext.tier(Tier.REDIS);
                }
                assertNull(registry.getCurrentObservation());
                pending.complete(Optional.of("row"));
                assertNull(registry.getCurrentObservation());
                assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
            }).get(5, TimeUnit.SECONDS);
        }
        assertEquals(5, sink.stopped.size());
        assertEquals("REDIS", tags(sink.last()).get("cachedb.tier"));
        assertNull(registry.getCurrentObservation());
    }
    @Test void cancellationsAndExceptionalCompletionStopOnceAndRestoreCallerContext() {
        var registry = ObservationRegistry.create(); var sink = new Sink();
        registry.observationConfig().observationHandler(sink); var recorder = recorder(registry);
        var cancelled = new CompletableFuture<String>();
        CacheObservations.callAsync(recorder, CacheOperation.QUERY, "Order", () -> cancelled);
        cancelled.cancel(true);
        assertEquals(1, sink.stopped.size()); assertEquals("error", tags(sink.last()).get("cachedb.outcome"));
        var failed = new CompletableFuture<String>();
        CacheObservations.callAsync(recorder, CacheOperation.SAVE, "Order", () -> failed);
        failed.completeExceptionally(new IllegalArgumentException("private"));
        assertEquals(2, sink.stopped.size()); assertEquals(2, sink.started.size());
        assertNull(registry.getCurrentObservation());
    }
    @Test void declaredTagsOnlyNoIdsTenantsPayloadsOrExceptionMessageTags() {
        var registry = ObservationRegistry.create(); var sink = new Sink();
        registry.observationConfig().observationHandler(sink); var recorder = recorder(registry);
        var scope = recorder.start(CacheOperation.QUERY);
        scope.entity("tenant-secret-id"); scope.route("user-9382"); scope.databaseSystem("payload");
        try (var binding = CacheObservationContext.attach(scope)) { scope.outcome(Outcome.MISS); }
        finally { scope.close(); }
        var values = tags(sink.last());
        assertEquals("unknown", values.get("cachedb.entity"));
        assertEquals("other", values.get("db.system"));
        assertFalse(values.containsKey("cachedb.route"));
        assertFalse(values.containsKey("cachedb.tier"), "An unknown tier must not be fabricated");
        assertEquals(Set.of("db.system", "cachedb.entity", "cachedb.operation", "cachedb.outcome"), values.keySet());
        assertFalse(sink.last().getHighCardinalityKeyValues().iterator().hasNext());
        var tooMany = java.util.stream.IntStream.range(0, 1025).mapToObj(i -> "Entity" + i)
                .collect(java.util.stream.Collectors.toSet());
        assertThrows(IllegalArgumentException.class, () -> new CacheObservationTagPolicy(tooMany, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CacheObservationTagPolicy(Set.of(), Set.of("bad/route")));
    }
    @Test void originsExistBeforeOnStartAreNotMetricTagsAndAreNotRemoteParents() {
        var registry = ObservationRegistry.create(); var sink = new Sink();
        registry.observationConfig().observationHandler(sink); var recorder = recorder(registry);
        String traceparent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        Observation caller = Observation.start("caller", registry);
        try (var parent = caller.openScope()) {
            var scope = recorder.start(CacheOperation.FLUSH_BATCH);
            scope.entity("Order"); scope.originLinks(CacheTraceparent.originLinks(List.of(traceparent, traceparent)));
            assertEquals(0, sink.started.size(), "Origins must be set before native handlers start");
            try (var binding = CacheObservationContext.attach(scope)) {
                scope.tier(Tier.SOURCE); scope.databaseSystem("postgresql");
            } finally { scope.close(); }
            assertSame(caller, registry.getCurrentObservation());
        } finally { caller.stop(); }
        assertEquals(1, sink.stopped.size());
        assertEquals(1, sink.originsAtStart.get(0).size());
        assertEquals(traceparent, sink.originsAtStart.get(0).get(0).traceparent());
        assertNull(sink.last().getParentObservation(), "Micrometer context parent only, not native SDK proof");
        assertFalse(tags(sink.last()).containsKey(CacheMicrometerObservationContext.ORIGIN_LINKS_KEY));
        assertFalse(sink.last().getHighCardinalityKeyValues().iterator().hasNext());
        var tooLate = recorder.start(CacheOperation.FLUSH_BATCH);
        try (var binding = CacheObservationContext.attach(tooLate)) {
            assertThrows(IllegalStateException.class, () -> tooLate.originLinks(List.of()));
        } finally { tooLate.close(); }
        assertNull(registry.getCurrentObservation());
    }
    @Test void noopRegistryAndBrokenHandlersDoNotChangeBusinessResultOrLeakScope() {
        assertFalse(new MicrometerCacheObservationRecorder(ObservationRegistry.NOOP).enabled());
        assertSame(NoOpCacheObservationRecorder.SCOPE,
                new MicrometerCacheObservationRecorder(ObservationRegistry.NOOP).start(CacheOperation.QUERY));
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context context) { return true; }
            public void onStop(Observation.Context context) { throw new IllegalStateException("exporter broken"); }
        });
        var recorder = recorder(registry);
        assertEquals("row", CacheObservations.call(recorder, CacheOperation.FIND_BY_ID, "Order", () -> "row"));
        assertEquals(1, recorder.instrumentationFailures());
        assertNull(registry.getCurrentObservation());
    }
}

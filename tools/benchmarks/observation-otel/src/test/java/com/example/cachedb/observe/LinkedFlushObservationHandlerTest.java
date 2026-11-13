package com.example.cachedb.observe;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.observe.*;
import com.reactor.cachedb.spring.boot.observe.*;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LinkedFlushObservationHandlerTest {
    static final AttributeKey<String> OP = AttributeKey.stringKey("cachedb.operation");
    static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("cachedb.outcome");

    static final class Fixture implements AutoCloseable {
        final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        final SdkTracerProvider sdk = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .setSpanLimits(SpanLimits.builder().setMaxNumberOfLinks(32)
                        .setMaxNumberOfAttributes(16).setMaxNumberOfEvents(8).build())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        final io.opentelemetry.api.trace.Tracer nativeTracer = sdk.get("cachedb-consumer-example");
        final OtelTracer tracer = new OtelTracer(nativeTracer, new OtelCurrentTraceContext(), event -> {});
        final ObservationRegistry registry = ObservationRegistry.create();
        final MicrometerCacheObservationRecorder recorder;
        Fixture() { this(true); }
        Fixture(boolean linked) {
            registry.observationConfig().observationHandler(linked
                    ? new LinkedFlushObservationHandler(tracer) : new DefaultTracingObservationHandler(tracer));
            recorder = new MicrometerCacheObservationRecorder(registry,
                    new CacheObservationTagPolicy(Set.of("Order"), Set.of("latest_orders")));
        }
        List<SpanData> spans(String operation) {
            return exporter.getFinishedSpanItems().stream()
                    .filter(s -> operation.equals(s.getAttributes().get(OP))).toList();
        }
        public void close() {
            try {
                assertNull(registry.getCurrentObservation());
                assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
                assertFalse(Span.current().getSpanContext().isValid());
                assertEquals(0, recorder.instrumentationFailures());
            } finally { sdk.close(); }
        }
    }

    static List<String> headers(int count) {
        var result = new ArrayList<String>();
        for (int i = 1; i <= count; i++)
            result.add("00-" + String.format(Locale.ROOT, "%032x", i) + "-"
                    + String.format(Locale.ROOT, "%016x", i + 1000) + "-" + (i % 2 == 0 ? "02" : "03"));
        return result;
    }

    @Test void exportsExactlyOneRootBatchWith32ActualLinksFlagsAndSqlChild() {
        try (var f = new Fixture()) {
            var origins = headers(40);
            origins.add(0, "invalid"); origins.add(origins.get(1));
            var expected = CacheTraceparent.originLinks(origins);
            assertEquals(32, expected.size());
            var ambient = f.nativeTracer.spanBuilder("ambient").startSpan();
            String batchId;
            try (var ambientBinding = ambient.makeCurrent()) {
                var scope = CacheObservations.start(f.recorder, CacheOperation.FLUSH_BATCH, "Order");
                try {
                    scope.originLinks(expected); scope.databaseSystem("postgresql");
                    try (var binding = CacheObservationContext.attach(scope)) {
                        scope.tier(Tier.SOURCE);
                        batchId = Span.current().getSpanContext().getSpanId();
                        assertNotEquals(ambient.getSpanContext().getSpanId(), batchId);
                        var sql = Observation.start("jdbc.execute", f.registry);
                        try (var sqlBinding = sql.openScope()) {
                            assertNotEquals(batchId, Span.current().getSpanContext().getSpanId());
                        } finally { sql.stop(); }
                        assertEquals(batchId, Span.current().getSpanContext().getSpanId());
                    }
                    assertEquals(ambient.getSpanContext(), Span.current().getSpanContext());
                } finally { scope.close(); scope.close(); }
                assertEquals(ambient.getSpanContext(), Span.current().getSpanContext());
                assertNull(f.registry.getCurrentObservation());
            } finally { ambient.end(); }
            assertEquals(3, f.exporter.getFinishedSpanItems().size());
            assertEquals(1, f.spans("flushBatch").size(), "No duplicate default/native flush spans");
            var batch = f.spans("flushBatch").get(0);
            assertEquals(batchId, batch.getSpanId());
            assertEquals(SpanId.getInvalid(), batch.getParentSpanId());
            assertNotEquals(ambient.getSpanContext().getTraceId(), batch.getTraceId());
            assertEquals("cachedb.flush", batch.getName());
            assertEquals(32, batch.getLinks().size());
            assertEquals(32, batch.getTotalRecordedLinks());
            for (int i = 0; i < 32; i++) {
                var linked = batch.getLinks().get(i).getSpanContext();
                assertEquals(expected.get(i).traceId(), linked.getTraceId());
                assertEquals(expected.get(i).spanId(), linked.getSpanId());
                assertEquals(TraceFlags.fromHex(expected.get(i).traceparent(), 53), linked.getTraceFlags());
                assertTrue(linked.isRemote());
            }
            var sql = f.exporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getName().equals("jdbc.execute")).findFirst().orElseThrow();
            assertEquals(batchId, sql.getParentSpanId());
            assertEquals(batch.getTraceId(), sql.getTraceId());
            assertEquals(Set.of("db.system", "cachedb.entity", "cachedb.operation", "cachedb.tier", "cachedb.outcome"),
                    batch.getAttributes().asMap().keySet().stream().map(AttributeKey::getKey)
                            .collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test void contextMetadataAloneDoesNotProduceNativeLinksOrClearAmbientNativeParent() {
        try (var f = new Fixture(false)) {
            var ambient = f.nativeTracer.spanBuilder("ambient").startSpan();
            try (var ambientBinding = ambient.makeCurrent()) {
                var scope = CacheObservations.start(f.recorder, CacheOperation.FLUSH_BATCH, "Order");
                try {
                    scope.originLinks(CacheTraceparent.originLinks(headers(2)));
                    try (var binding = CacheObservationContext.attach(scope)) { scope.tier(Tier.SOURCE); }
                } finally { scope.close(); }
                assertEquals(ambient.getSpanContext(), Span.current().getSpanContext());
            } finally { ambient.end(); }
            assertEquals(1, f.spans("flushBatch").size());
            var batch = f.spans("flushBatch").get(0);
            assertTrue(batch.getLinks().isEmpty(), "Context metadata is not a native link");
            assertEquals(ambient.getSpanContext().getSpanId(), batch.getParentSpanId());
        }
    }

    @Test void ordinaryObservationStillUsesAmbientParentAndNoLinks() {
        try (var f = new Fixture()) {
            var ambient = f.nativeTracer.spanBuilder("ambient").startSpan();
            try (var binding = ambient.makeCurrent()) {
                assertEquals("row", CacheObservations.call(f.recorder, CacheOperation.FIND_BY_ID, "Order", () -> {
                    CacheObservationContext.tier(Tier.REDIS); return "row";
                }));
                assertEquals(ambient.getSpanContext(), Span.current().getSpanContext());
            } finally { ambient.end(); }
            var call = f.spans("findById").get(0);
            assertEquals(1, f.spans("findById").size());
            assertEquals(ambient.getSpanContext().getSpanId(), call.getParentSpanId());
            assertEquals(ambient.getSpanContext().getTraceId(), call.getTraceId());
            assertTrue(call.getLinks().isEmpty());
        }
    }

    @Test void completedBatchErrorHasSanitizedNativeErrorAndClosesOnce() {
        try (var f = new Fixture()) {
            var failure = new IllegalStateException("secret-tenant-payload");
            var caught = assertThrows(IllegalStateException.class, () ->
                    CacheObservations.call(f.recorder, CacheOperation.FLUSH_BATCH, "Order", () -> { throw failure; }));
            assertSame(failure, caught);
            assertEquals(1, f.spans("flushBatch").size());
            var batch = f.spans("flushBatch").get(0);
            assertEquals(SpanId.getInvalid(), batch.getParentSpanId());
            assertEquals(StatusCode.ERROR, batch.getStatus().getStatusCode());
            assertEquals("error", batch.getAttributes().get(OUTCOME));
            assertFalse(batch.toString().contains("secret-tenant-payload"));
            assertFalse(batch.getEvents().toString().contains("secret-tenant-payload"));
        }
    }

    @Test void asyncErrorRestoresCallerAndWorkerContextsAndPreservesOriginalFailure() throws Exception {
        asyncTermination(false);
    }
    @Test void asyncCancellationRestoresContextsAndEndsOneNativeSpan() throws Exception {
        asyncTermination(true);
    }
    static void asyncTermination(boolean cancel) throws Exception {
        try (var f = new Fixture(); var executor = Executors.newSingleThreadExecutor()) {
            var original = new CompletableFuture<String>();
            var snapshot = new AtomicReference<CacheObservationContext.Snapshot>();
            var spanId = new AtomicReference<String>();
            var caller = f.nativeTracer.spanBuilder("caller").startSpan();
            CompletableFuture<String> returned;
            try (var callerBinding = caller.makeCurrent()) {
                returned = CacheObservations.callAsync(f.recorder, CacheOperation.QUERY, "Order", () -> {
                    snapshot.set(CacheObservationContext.capture());
                    spanId.set(Span.current().getSpanContext().getSpanId());
                    return original;
                });
                assertSame(original, returned);
                assertNull(f.registry.getCurrentObservation());
                assertEquals(caller.getSpanContext(), Span.current().getSpanContext());
            } finally { caller.end(); }
            var failure = new IllegalStateException("private-worker-payload");
            executor.submit(() -> {
                var worker = f.nativeTracer.spanBuilder("worker").startSpan();
                try (var workerBinding = worker.makeCurrent()) {
                    try (var binding = snapshot.get().restore()) {
                        CacheObservationContext.tier(Tier.SOURCE);
                        assertEquals(spanId.get(), Span.current().getSpanContext().getSpanId());
                    }
                    assertEquals(worker.getSpanContext(), Span.current().getSpanContext());
                    if (cancel) assertTrue(original.cancel(true));
                    else assertTrue(original.completeExceptionally(failure));
                    assertEquals(worker.getSpanContext(), Span.current().getSpanContext());
                    assertNull(f.registry.getCurrentObservation());
                    assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
                } finally { worker.end(); }
                assertFalse(Span.current().getSpanContext().isValid());
            }).get(5, TimeUnit.SECONDS);
            if (cancel) assertTrue(returned.isCancelled());
            else assertSame(failure, assertThrows(ExecutionException.class,
                    () -> returned.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(1, f.spans("query").size());
            var call = f.spans("query").get(0);
            assertEquals(spanId.get(), call.getSpanId());
            assertEquals(caller.getSpanContext().getSpanId(), call.getParentSpanId());
            assertEquals(StatusCode.ERROR, call.getStatus().getStatusCode());
            assertEquals("SOURCE", call.getAttributes().get(AttributeKey.stringKey("cachedb.tier")));
            assertFalse(call.getEvents().toString().contains("private-worker-payload"));
        }
    }

    @Test void springBootReplacesDefaultHandlerInsteadOfCreatingDuplicateNativeSpans() {
        try (var f = new Fixture()) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class,
                            MicrometerTracingAutoConfiguration.class, CacheObservationAutoConfiguration.class))
                    .withUserConfiguration(FlushObservationConfiguration.class)
                    .withBean(io.micrometer.tracing.Tracer.class, () -> f.tracer)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals(1, context.getBeansOfType(DefaultTracingObservationHandler.class).size());
                        assertInstanceOf(LinkedFlushObservationHandler.class,
                                context.getBean(DefaultTracingObservationHandler.class));
                        var recorder = context.getBean(CacheObservationRecorder.class);
                        CacheObservations.call(recorder, CacheOperation.FLUSH_BATCH, "Order", () -> "done");
                        assertEquals(1, f.spans("flushBatch").size());
                        assertEquals(SpanId.getInvalid(), f.spans("flushBatch").get(0).getParentSpanId());
                        assertNull(context.getBean(ObservationRegistry.class).getCurrentObservation());
                    });
        }
    }
}

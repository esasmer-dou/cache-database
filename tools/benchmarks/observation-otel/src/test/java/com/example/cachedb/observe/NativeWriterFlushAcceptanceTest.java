package com.example.cachedb.observe;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.observe.*;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.*;
import com.reactor.cachedb.core.trace.TraceContexts;
import com.reactor.cachedb.jdbc.*;
import io.opentelemetry.api.trace.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

class NativeWriterFlushAcceptanceTest {
    @Test void explicitSupplierReadsNativeUnsampledFlagsAndNeverMdcSampling() {
        try (var f = new LinkedFlushObservationHandlerTest.Fixture()) {
            var supplier = new FlushObservationConfiguration().cacheNativeTraceparentSupplier(f.tracer);
            assertNull(supplier.get());
            for (int flags = 0; flags <= 255; flags++) {
                String flagHex = String.format(Locale.ROOT, "%02x", flags);
                String header = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-" + flagHex;
                var remote = SpanContext.createFromRemoteParent("0123456789abcdef0123456789abcdef",
                        "0123456789abcdef", TraceFlags.fromHex(flagHex, 0), TraceState.getDefault());
                try (var nativeBinding = Span.wrap(remote).makeCurrent();
                        var metadata = TraceContexts.withMetadata(Map.of("correlationId", "retained"))) {
                    assertEquals(header, supplier.get());
                    var propagator = new ObservingTraceContextPropagator(TraceContexts.threadLocal(), supplier);
                    assertEquals(Map.of("correlationId", "retained", "traceparent", header), propagator.capture());
                    assertEquals((flags & 1) != 0, new CacheSpanLink(supplier.get()).sampled());
                }
            }
            assertNull(supplier.get());
        }
    }

    @Test void nativeSupplierWithoutASpanNeverUsesMdcFabricatedSamplingAndKeepsExplicitMetadata() {
        try (var f = new LinkedFlushObservationHandlerTest.Fixture()) {
            var supplier = new FlushObservationConfiguration().cacheNativeTraceparentSupplier(f.tracer);
            var propagator = new ObservingTraceContextPropagator(
                    new com.reactor.cachedb.spring.boot.MdcTraceContextPropagator(), supplier, true);
            var previous = org.slf4j.MDC.getCopyOfContextMap();
            try {
                org.slf4j.MDC.put("traceId", "0123456789abcdef0123456789abcdef");
                org.slf4j.MDC.put("spanId", "0123456789abcdef");
                try (var metadata = TraceContexts.withMetadata(Map.of("correlationId", "preserved"))) {
                    var captured = propagator.capture();
                    assertFalse(captured.containsKey("traceparent"));
                    assertEquals("preserved", captured.get("correlationId"));
                    assertEquals("0123456789abcdef", captured.get("spanId"));
                }
                String explicit = "00-fedcba9876543210fedcba9876543210-fedcba9876543210-00";
                try (var metadata = TraceContexts.withMetadata(Map.of("correlationId", "preserved", "traceparent", explicit))) {
                    assertEquals(explicit, propagator.capture().get("traceparent"));
                    assertEquals("preserved", propagator.capture().get("correlationId"));
                }
            } finally {
                org.slf4j.MDC.clear();
                if (previous != null) org.slf4j.MDC.setContextMap(previous);
            }
        }
    }

    @Test void everyNativeFlagByteSurvivesActualSdkLinksInBoundedRootBatches() {
        try (var f = new LinkedFlushObservationHandlerTest.Fixture()) {
            var supplier = new FlushObservationConfiguration().cacheNativeTraceparentSupplier(f.tracer);
            for (int first = 0; first <= 255; first += 32) {
                var origins = new ArrayList<String>();
                for (int flags = first; flags < first + 32; flags++) {
                    String hex = String.format(Locale.ROOT, "%02x", flags);
                    var nativeContext = SpanContext.createFromRemoteParent(String.format(Locale.ROOT, "%032x", flags + 1),
                            String.format(Locale.ROOT, "%016x", flags + 1000), TraceFlags.fromHex(hex, 0), TraceState.getDefault());
                    try (var binding = Span.wrap(nativeContext).makeCurrent()) { origins.add(supplier.get()); }
                }
                var scope = CacheObservations.start(f.recorder, CacheOperation.FLUSH_BATCH, "Order");
                try {
                    scope.originLinks(CacheTraceparent.originLinks(origins));
                    try (var binding = CacheObservationContext.attach(scope)) { scope.tier(Tier.SOURCE); }
                } finally { scope.close(); }
            }
            var batches = f.spans("flushBatch");
            assertEquals(8, batches.size());
            for (int batch = 0; batch < 8; batch++) {
                assertEquals(SpanId.getInvalid(), batches.get(batch).getParentSpanId());
                assertEquals(32, batches.get(batch).getLinks().size());
                for (int link = 0; link < 32; link++)
                    assertEquals(TraceFlags.fromHex(String.format(Locale.ROOT, "%02x", batch * 32 + link), 0),
                            batches.get(batch).getLinks().get(link).getSpanContext().getTraceFlags());
            }
        }
    }

    @Test void realWriterSpanHeaderSurvivesQueuedMetadataWithoutAnImplicitBridge() {
        try (var f = new LinkedFlushObservationHandlerTest.Fixture()) {
            var supplier = new FlushObservationConfiguration().cacheNativeTraceparentSupplier(f.tracer);
            var propagator = new ObservingTraceContextPropagator(TraceContexts.threadLocal(), supplier);
            QueuedWriteOperation operation;
            try (var metadata = TraceContexts.withMetadata(Map.of("correlationId", "retained"))) {
                operation = CacheObservations.call(f.recorder, CacheOperation.SAVE, "Order",
                        () -> operation("1", "business", propagator.capture()));
                assertEquals(Map.of("correlationId", "retained"), TraceContexts.threadLocal().capture());
            }
            var writer = f.spans("save").getFirst();
            var origin = new CacheSpanLink(operation.traceMetadata().get("traceparent"));
            assertEquals(writer.getTraceId(), origin.traceId()); assertEquals(writer.getSpanId(), origin.spanId());
            assertEquals("retained", operation.traceMetadata().get("correlationId"));
            assertEquals(1, f.spans("save").size());
            assertTrue(f.spans("flushBatch").isEmpty());
        }
    }

    static QueuedWriteOperation operation(String id, String table, Map<String, String> metadata) {
        return new QueuedWriteOperation(OperationType.UPSERT, "Order", table, "orders", "default",
                "id", "version", "deleted", id, Map.of("id", id), 1, Instant.EPOCH,
                null, null, 0, null, List.of(), metadata);
    }
    static final class Flusher extends AbstractJdbcWriteBehindFlusher {
        private final io.micrometer.observation.ObservationRegistry registry;
        Flusher(PGSimpleDataSource source, io.micrometer.observation.ObservationRegistry registry) throws SQLException {
            super(source, null, JdbcWriteFence.Provider.POSTGRES, 2); this.registry = registry; writeFence.initialize();
        }
        public WriteFailureDetails classify(Exception failure) { return WriteFailureDetails.unknown(failure); }
        public void flush(QueuedWriteOperation operation) throws SQLException { flushBatch(List.of(operation)); }
        public void flushBatch(List<QueuedWriteOperation> operations) throws SQLException {
            try (var connection = dataSource.getConnection()) {
                inTransaction(connection, operations, null, null, filtered -> {
                    var sql = io.micrometer.observation.Observation.start("jdbc.execute", registry);
                    try (var binding = sql.openScope();
                            var statement = connection.prepareStatement("INSERT INTO business(id) VALUES(?)")) {
                        for (var operation : filtered) { statement.setString(1, operation.id()); statement.addBatch(); }
                        statement.executeBatch();
                    } finally { sql.stop(); }
                });
            }
        }
    }

    @Test @EnabledIfSystemProperty(named="cachedb.observation.postgres", matches="true")
    void actualJdbcTransactionExportsOneRootBatchWith32RealWriterLinksAndSqlChild() throws Exception {
        var source = new PGSimpleDataSource();
        source.setURL(Objects.requireNonNull(System.getProperty("cachedb.observation.jdbc.url",
                System.getenv("CACHEDB_SNAPSHOT_POSTGRES_URL")), "JDBC test URL"));
        String user = System.getProperty("cachedb.observation.jdbc.user", System.getenv("CACHEDB_SNAPSHOT_POSTGRES_USER"));
        String password = System.getProperty("cachedb.observation.jdbc.password", System.getenv("CACHEDB_SNAPSHOT_POSTGRES_PASSWORD"));
        if (user != null) source.setUser(user);
        if (password != null) source.setPassword(password);
        String schema = "observation_" + UUID.randomUUID().toString().replace("-", "");
        try (var administration = source.getConnection(); var ddl = administration.createStatement()) {
            ddl.execute("CREATE SCHEMA " + schema);
            try (var f = new LinkedFlushObservationHandlerTest.Fixture()) {
                source.setCurrentSchema(schema);
                try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE business(id VARCHAR(64) PRIMARY KEY)");
                }
                var supplier = new FlushObservationConfiguration().cacheNativeTraceparentSupplier(f.tracer);
                var propagator = new ObservingTraceContextPropagator(TraceContexts.threadLocal(), supplier);
                var operations = new ArrayList<QueuedWriteOperation>();
                try (var metadata = TraceContexts.withMetadata(Map.of("correlationId", "preserved"))) {
                    for (int i = 1; i <= 40; i++) {
                        String id = Integer.toString(i);
                        operations.add(CacheObservations.call(f.recorder, CacheOperation.SAVE, "Order",
                                () -> operation(id, schema + ".business", propagator.capture())));
                    }
                }
                var flusher = new Flusher(source, f.registry);
                flusher.withObservationRecorder(f.recorder).withObservationOriginLinksEnabled(true);
                flusher.flushBatch(operations);
                assertEquals(40, f.spans("save").size());
                assertEquals(1, f.spans("flushBatch").size());
                var batch = f.spans("flushBatch").getFirst();
                assertEquals(SpanId.getInvalid(), batch.getParentSpanId());
                assertEquals(32, batch.getLinks().size());
                for (int i = 0; i < 32; i++) {
                    var origin = new CacheSpanLink(operations.get(i).traceMetadata().get("traceparent"));
                    var linked = batch.getLinks().get(i).getSpanContext();
                    assertEquals(origin.traceId(), linked.getTraceId()); assertEquals(origin.spanId(), linked.getSpanId());
                    assertEquals("preserved", operations.get(i).traceMetadata().get("correlationId"));
                }
                assertTrue(f.exporter.getFinishedSpanItems().stream()
                        .anyMatch(span -> span.getParentSpanId().equals(batch.getSpanId())));
                try (var connection = source.getConnection(); var statement = connection.createStatement();
                        var rows = statement.executeQuery("SELECT COUNT(*) FROM business")) {
                    assertTrue(rows.next()); assertEquals(40, rows.getInt(1));
                }
            } finally { ddl.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}

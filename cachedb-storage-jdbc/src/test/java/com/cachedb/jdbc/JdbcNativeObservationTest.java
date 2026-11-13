package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.observe.*;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcNativeObservationTest {
    static final class Recorder implements CacheObservationRecorder {
        final List<Recorded> scopes = new ArrayList<>();
        public Scope start(CacheOperation operation) { var scope = new Recorded(operation); scopes.add(scope); return scope; }
    }
    static final class Recorded implements CacheObservationRecorder.Scope {
        final CacheOperation operation;
        int closes, bindings;
        Tier tier; Outcome outcome; Throwable failure; String database;
        List<CacheSpanLink> links = List.of(), linksAtAttach = List.of();
        Recorded(CacheOperation operation) { this.operation = operation; }
        public void tier(Tier value) { tier = value; }
        public void outcome(Outcome value) { outcome = value; }
        public void error(Throwable value) { failure = value; outcome = Outcome.ERROR; }
        public void databaseSystem(String value) { database = value; }
        public void originLinks(List<CacheSpanLink> value) { links = List.copyOf(value); assertEquals(0, bindings); }
        public CacheObservationRecorder.Binding makeCurrent() {
            bindings++; linksAtAttach = links; return NoOpCacheObservationRecorder.BINDING;
        }
        public void close() { closes++; }
    }
    static final class Flusher extends AbstractJdbcWriteBehindFlusher {
        Flusher(JdbcDataSource source) throws SQLException {
            super(source, null, JdbcWriteFence.Provider.POSTGRES, 1);
            writeFence.initialize();
        }
        public void flush(QueuedWriteOperation operation) throws SQLException { run(List.of(operation), null); }
        public WriteFailureDetails classify(Exception failure) { return WriteFailureDetails.unknown(failure); }
        void run(List<QueuedWriteOperation> operations, SQLException failure) throws SQLException {
            try (var connection = dataSource.getConnection()) {
                inTransaction(connection, operations, Connection.TRANSACTION_SERIALIZABLE, null, filtered -> {
                    try (var statement = connection.createStatement()) { statement.executeUpdate("INSERT INTO business VALUES(1)"); }
                    if (failure != null) throw failure;
                });
                assertTrue(connection.getAutoCommit());
            }
        }
    }
    static JdbcDataSource source() throws SQLException {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:observe_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE business(id INTEGER)");
        }
        return source;
    }
    static long count(JdbcDataSource source) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM business")) { rows.next(); return rows.getLong(1); }
    }

    @Test void trueTransactionCommitsOnceAndRetainsConcreteFlusher() throws Exception {
        var source = source(); var recorder = new Recorder(); var flusher = new Flusher(source);
        assertSame(flusher, flusher.withObservationRecorder(recorder));
        flusher.run(List.of(), null);
        assertEquals(1, count(source));
        assertEquals(1, recorder.scopes.size());
        var scope = recorder.scopes.getFirst();
        assertEquals(CacheOperation.FLUSH_BATCH, scope.operation);
        assertEquals("postgresql", scope.database);
        assertEquals(Tier.SOURCE, scope.tier);
        assertEquals(1, scope.bindings); assertEquals(1, scope.closes);
        assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
    }

    @Test void rollbackAndOriginalSqlFailureAreNotChangedByInstrumentation() throws Exception {
        var source = source(); var recorder = new Recorder(); var flusher = new Flusher(source);
        flusher.withObservationRecorder(recorder);
        var failure = new SQLException("business", "40001");
        assertSame(failure, assertThrows(SQLException.class, () -> flusher.run(List.of(), failure)));
        assertEquals(0, count(source));
        assertSame(failure, recorder.scopes.getFirst().failure);
        assertEquals(Outcome.ERROR, recorder.scopes.getFirst().outcome);
        assertEquals(1, recorder.scopes.getFirst().closes);
        assertSame(NoOpCacheObservationRecorder.SCOPE, CacheObservationContext.current());
    }

    @Test void disabledRecorderNeverStartsAScopeAndDoesNotWrapTransaction() throws Exception {
        var source = source(); var flusher = new Flusher(source);
        flusher.withObservationRecorder(new CacheObservationRecorder() {
            public boolean enabled() { return false; }
            public Scope start(CacheOperation operation) { throw new AssertionError("Disabled recorder invoked"); }
        });
        flusher.run(List.of(), null);
        assertEquals(1, count(source));
    }

    @Test void originalOriginsAreBoundedDeduplicatedAndAvailableBeforeFirstAttach() throws Exception {
        var source = source(); var recorder = new Recorder(); var flusher = new Flusher(source);
        flusher.withObservationRecorder(recorder);
        flusher.withObservationOriginLinksEnabled(true);
        var columns = new LinkedHashMap<String, String>();
        for (int i = 0; i < 4097; i++) columns.put("column" + i, "value");
        var operations = new ArrayList<QueuedWriteOperation>();
        for (int i = 1; i <= 40; i++) {
            String header = "00-" + String.format(Locale.ROOT, "%032x", i) + "-"
                    + String.format(Locale.ROOT, "%016x", i + 1000) + "-00";
            operations.add(new QueuedWriteOperation(OperationType.UPSERT, "Order", "business", "orders",
                    "default", "id", "version", "deleted", Integer.toString(i), columns, i, Instant.EPOCH,
                    null, null, 0, null, List.of(), Map.of("correlationId", "keep", "traceparent", header)));
        }
        assertThrows(IllegalArgumentException.class, () -> flusher.run(operations, null));
        var scope = recorder.scopes.getFirst();
        assertEquals(32, scope.linksAtAttach.size());
        assertFalse(scope.linksAtAttach.getFirst().sampled());
        assertEquals("keep", operations.getFirst().traceMetadata().get("correlationId"));
        assertEquals(0, count(source)); assertEquals(1, scope.closes);
    }
}

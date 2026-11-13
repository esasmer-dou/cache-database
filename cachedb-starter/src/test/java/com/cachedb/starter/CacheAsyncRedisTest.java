package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.async.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.trace.TraceContexts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordingFile;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

/** Native gate deliberately remains red until parent T-1/Redis direct-enqueue integration is present. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(60)
@Isolated("ZERO-threshold JFR must not overlap J0 20 ms recordings")
class CacheAsyncRedisTest {
    record Row(Long id, String tenant, String value) {}
    private static final EntityMetadata<Row, Long> METADATA = new EntityMetadata<>() {
        public String entityName() { return "AsyncRow"; }
        public String tableName() { return "async_rows"; }
        public String redisNamespace() { return "async_rows"; }
        public String idColumn() { return "id"; }
        public Class<Row> entityType() { return Row.class; }
        public Function<Row, Long> idAccessor() { return Row::id; }
        public List<String> columns() { return List.of("id", "tenant", "value"); }
        public String tenantFieldName() { return "tenant"; }
        public String extractTenantId(Row row) { return row.tenant(); }
        public List<RelationDefinition> relations() { return List.of(); }
    };
    private static final EntityCodec<Row> CODEC = new EntityCodec<>() {
        public String toRedisValue(Row row) {
            return LengthPrefixedPayloadCodec.encode(Map.of("id", row.id().toString(), "tenant", row.tenant(), "value", row.value()));
        }
        public Row fromRedisValue(String payload) {
            var fields = LengthPrefixedPayloadCodec.decode(payload);
            return new Row(Long.valueOf(fields.get("id")), fields.get("tenant"), fields.get("value"));
        }
        public Map<String, Object> toColumns(Row row) {
            return Map.of("id", row.id(), "tenant", row.tenant(), "value", row.value());
        }
    };

    @Test void realRedisFallbackPreservesTenantAndCausalRequirements() throws Exception {
        try (var fixture = new Fixture(); var executor = new CacheAsyncExecutor(8, TraceContexts.threadLocal())) {
            var async = new OffloadingAsyncEntityRepository<>(fixture.repository, executor);
            Row row = new Row(1L, "a", "value");
            com.reactor.cachedb.core.model.WriteReceipt<Row, Long> receipt;
            try (var tenant = CacheTenantContext.withTenant("a")) {
                receipt = fixture.repository.saveWithReceipt(row);
                try (var causal = CacheCausalContext.withReceipt("async_rows", receipt)) {
                    assertEquals(row, async.findByIdAsync(1L).get(5, TimeUnit.SECONDS).orElseThrow());
                    assertEquals(List.of(row, row), async.findAllAsync(List.of(1L, 1L)).get(5, TimeUnit.SECONDS));
                }
                try (var causal = CacheCausalContext.withMinVersion("async_rows", 1L, receipt.version() + 1)) {
                    var failure = assertThrows(ExecutionException.class, () -> async.findByIdAsync(1L).get(5, TimeUnit.SECONDS));
                    assertInstanceOf(IllegalStateException.class, failure.getCause());
                }
            }
            try (var foreign = CacheTenantContext.withTenant("b")) {
                var failure = assertThrows(ExecutionException.class, () -> async.findByIdAsync(1L).get(5, TimeUnit.SECONDS));
                assertInstanceOf(CacheTenantAccessDeniedException.class, failure.getCause());
            }
            assertTrue(executor.awaitIdle(java.time.Duration.ofSeconds(5)));
            assertEquals(8, executor.snapshot().availablePermits());
        }
    }

    @Test void thousandNativePointMissesAndBulkReadNeverEnterOffloadingExecutor() throws Exception {
        var offloads = new AtomicInteger();
        Executor forbiddenOffload = command -> {
            offloads.incrementAndGet();
            throw new RejectedExecutionException("Native T-1 reads must not offload a parked waiter");
        };
        try (var fixture = new Fixture();
                var executor = new CacheAsyncExecutor(1024, forbiddenOffload, TraceContexts.threadLocal());
                var tenant = CacheTenantContext.withTenant("a");
                var recording = new Recording()) {
            var rows = new ArrayList<Row>(1000);
            for (long id = 1; id <= 1000; id++) rows.add(new Row(id, "a", "v" + id));
            fixture.repository.saveAll(rows);
            // Near cache is disabled: each point read must use the actual Redis miss path.
            var async = fixture.database.asyncEntityRepository(METADATA, CODEC, executor);
            startPinningRecording(recording, Duration.ofSeconds(60));
            var window = new ReadWindow();
            window.begin();
            var reads = new ArrayList<CompletableFuture<Optional<Row>>>(1000);
            for (long id = 1; id <= 1000; id++) {
                reads.add(async.findByIdAsync(id));
                window.submitted++;
            }
            CompletableFuture.allOf(reads.toArray(CompletableFuture<?>[]::new)).get(20, TimeUnit.SECONDS);
            for (int i = 0; i < reads.size(); i++) {
                assertEquals(rows.get(i), reads.get(i).join().orElseThrow());
                window.completed++;
            }
            var bulk = async.findAllAsync(List.of(3L, 1L, 3L)).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(rows.get(2), rows.get(0), rows.get(2)), bulk);
            window.bulkCompleted++;
            window.bulkRows = bulk.size();
            assertEquals(0, offloads.get());
            assertEquals(0, executor.snapshot().accepted());
            window.end();
            window.commit();
            verifyPinningRecording(recording, Path.of("target/t3-async-jfr/thousand-native-reads.jfr"));
        }
    }

    @Name("com.reactor.cachedb.t3.NativeAsyncReadWindow")
    static final class ReadWindow extends Event {
        int submitted;
        int completed;
        int bulkCompleted;
        int bulkRows;
    }

    private static void startPinningRecording(Recording recording, Duration limit) {
        assertTrue(FlightRecorder.isAvailable());
        assertTrue(FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .anyMatch(type -> type.getName().equals("jdk.VirtualThreadPinned")));
        recording.setMaxSize(64L * 1024 * 1024);
        recording.setDuration(limit);
        recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
        recording.enable("jdk.ActiveSetting");
        recording.enable("jdk.DataLoss");
        recording.enable(ReadWindow.class).withThreshold(Duration.ZERO).withStackTrace();
        recording.start();
        assertPinningSettings(recording);
    }

    private static void assertPinningSettings(Recording recording) {
        assertEquals(RecordingState.RUNNING, recording.getState(), "Workload outlived its JFR window");
        var settings = recording.getSettings();
        assertEquals("true", settings.get("jdk.VirtualThreadPinned#enabled"));
        assertEquals("true", settings.get("jdk.VirtualThreadPinned#stackTrace"));
        assertTrue(settings.get("jdk.VirtualThreadPinned#threshold").matches("0\\s*(ns|us|ms|s)"));
    }

    private static void verifyPinningRecording(Recording recording, Path output) throws Exception {
        assertPinningSettings(recording);
        recording.stop();
        Files.createDirectories(output.getParent());
        recording.dump(output);
        assertTrue(Files.size(output) > 0 && Files.size(output) <= 64L * 1024 * 1024);
        long pinnedId = FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .filter(type -> type.getName().equals("jdk.VirtualThreadPinned")).findFirst().orElseThrow().getId();
        boolean enabled = false, stacks = false, zeroThreshold = false;
        int events = 0, windows = 0;
        try (var file = new RecordingFile(output)) {
            while (file.hasMoreEvents()) {
                assertTrue(++events <= 10_000, "JFR evidence exceeded its event budget");
                var event = file.readEvent();
                String name = event.getEventType().getName();
                if (name.equals("jdk.DataLoss")) fail("JFR data loss invalidates pinning evidence");
                if (name.equals("jdk.ActiveSetting") && event.getLong("id") == pinnedId) {
                    String setting = event.getString("name"), value = event.getString("value");
                    enabled |= setting.equals("enabled") && value.equals("true");
                    stacks |= setting.equals("stackTrace") && value.equals("true");
                    zeroThreshold |= setting.equals("threshold") && value.matches("0\\s*(ns|us|ms|s)");
                }
                if (name.equals("jdk.VirtualThreadPinned")) {
                    var trace = event.getStackTrace();
                    assertNotNull(trace, "Pinning event has no stack");
                    assertFalse(trace.getFrames().isEmpty(), "Empty pinning stack invalidates evidence");
                    assertFalse(trace.isTruncated(), "Truncated pinning stack invalidates evidence");
                    assertFalse(trace.getFrames().stream().anyMatch(frame ->
                            frame.getMethod().getType().getName().startsWith("com.reactor.cachedb.")),
                            "CacheDB carrier pinning observed in " + output);
                }
                if (name.equals("com.reactor.cachedb.t3.NativeAsyncReadWindow")) {
                    windows++;
                    assertEquals(1000, event.getInt("submitted"));
                    assertEquals(1000, event.getInt("completed"));
                    assertEquals(1, event.getInt("bulkCompleted"));
                    assertEquals(3, event.getInt("bulkRows"));
                    assertTrue(event.getDuration().compareTo(Duration.ZERO) > 0);
                    assertFalse(event.getStartTime().isBefore(recording.getStartTime()));
                    assertFalse(event.getEndTime().isAfter(recording.getStopTime()));
                }
            }
        }
        assertEquals(1, windows, "Missing/duplicate completed workload window");
        assertTrue(enabled && stacks && zeroThreshold, "Missing native enabled/stack/zero-threshold settings");
    }

    private static final class Fixture implements AutoCloseable {
        final String prefix = "t3-" + UUID.randomUUID();
        final JedisPooled redis = new JedisPooled("redis://"
                + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":"
                + Integer.getInteger("cachedb.snapshot.redis.port", 16383));
        final CacheDatabase database;
        final EntityRepository<Row, Long> repository;

        Fixture() {
            var sql = new JdbcDataSource();
            sql.setURL("jdbc:h2:mem:" + prefix);
            var config = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    // CLIENT_ONLY backlog must not change retention in this transport fixture.
                    .redisGuardrail(RedisGuardrailConfig.builder()
                            .automaticRuntimeProfileSwitchingEnabled(false).build())
                    .writeBehind(WriteBehindConfig.builder().streamKey(prefix + ":writes")
                            .compactionStreamKey(prefix + ":compact").deadLetterStreamKey(prefix + ":dlq").build())
                    .build();
            database = new CacheDatabase(redis, sql, config, CacheRuntimeOptions.builder()
                    .redisReadCoalescing(RedisReadCoalescingConfig.builder().enabled(true).build()).build());
            class EmptySource implements com.reactor.cachedb.core.page.EntityByIdLoader<Row, Long>,
                    com.reactor.cachedb.core.page.EntityOwnershipLoader<Long> {
                public Optional<Row> load(Long id) { return Optional.empty(); }
                public Map<Long, String> loadTenantOwners(Collection<Long> ids) { return Map.of(); }
            }
            database.register(METADATA, CODEC, CachePolicy.defaults(), null, null, new EmptySource(), null);
            repository = database.repository(METADATA, CODEC);
            database.start();
        }

        @Override public void close() {
            try { database.close(); }
            finally {
                try {
                    String cursor = "0";
                    boolean complete = false;
                    for (int i = 0; i < 256; i++) {
                        var batch = redis.scan(cursor, new ScanParams().match(prefix + ":*").count(512));
                        if (!batch.getResult().isEmpty()) redis.del(batch.getResult().toArray(String[]::new));
                        cursor = batch.getCursor();
                        if ("0".equals(cursor)) { complete = true; break; }
                    }
                    if (!complete) throw new IllegalStateException("Test cleanup exceeded bounded scan budget");
                } finally { redis.close(); }
            }
        }
    }
}

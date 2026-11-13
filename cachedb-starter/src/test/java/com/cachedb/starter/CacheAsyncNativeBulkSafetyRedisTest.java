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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

/** Real native result lifetime and admission qualification. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(60)
class CacheAsyncNativeBulkSafetyRedisTest {
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

    private static EntityCodec<Row> observing(java.util.function.Consumer<String> observer) {
        return new EntityCodec<>() {
            public String toRedisValue(Row row) { return CODEC.toRedisValue(row); }
            public Row fromRedisValue(String payload) { observer.accept(payload); return CODEC.fromRedisValue(payload); }
            public Map<String, Object> toColumns(Row row) { return CODEC.toColumns(row); }
        };
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }
    private static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }

    @Test void saturatedBulkRejectsBeforeInputIterationAndCancellationCannotFreeBlockedDecodeMemory() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var fixture = new Fixture(observing(payload -> { entered.countDown(); await(release); }),
                    48L * 1024 * 1024, 5000);
                var tenant = CacheTenantContext.withTenant("a")) {
            try {
                fixture.repository.save(new Row(1L, "a", "value"));
                var first = fixture.repository.async().findAllAsync(List.of(1L));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var iterations = new AtomicInteger();
                var forbiddenInput = new AbstractCollection<Long>() {
                    public int size() { return 1; }
                    public Iterator<Long> iterator() {
                        iterations.incrementAndGet(); throw new AssertionError("Rejected bulk must not copy input");
                    }
                };
                assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class,
                        () -> fixture.repository.async().findAllAsync(forbiddenInput).get(2, TimeUnit.SECONDS)).getCause());
                assertEquals(0, iterations.get());
                assertTrue(first.cancel(true));
                assertEquals(48L * 1024 * 1024, fixture.database.nativeReadMemory().reservedBytes());
                assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class,
                        () -> fixture.repository.async().findAllAsync(forbiddenInput).get(2, TimeUnit.SECONDS)).getCause());
                assertEquals(0, iterations.get());
                release.countDown();
                until(() -> fixture.database.nativeReadMemory().reservedBytes() == 0);
                assertEquals(0, fixture.database.nativeReadMemory().deliveries());
            } finally { release.countDown(); }
        }
    }

    @Test void pointMappingDeadlineIsFiniteWhileItsDecoderAndRawContentRemainOwned() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var fixture = new Fixture(observing(payload -> {
                    assertEquals("a", CacheTenantContext.currentTenant().orElseThrow());
                    entered.countDown(); await(release);
                }), 64L * 1024 * 1024, 500);
                var tenant = CacheTenantContext.withTenant("a")) {
            try {
                fixture.repository.save(new Row(1L, "a", "value"));
                var result = fixture.repository.async().findByIdAsync(1L);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertInstanceOf(redis.clients.jedis.exceptions.JedisConnectionException.class,
                        assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS)).getCause());
                assertTrue(fixture.database.nativeReadMemory().deliveries() > 0);
                assertTrue(fixture.database.redisReadRuntime().deliveryMemory().reservedBytes() > 0);
                release.countDown();
                until(() -> fixture.database.nativeReadMemory().deliveries() == 0
                        && fixture.database.redisReadRuntime().deliveryMemory().reservedBytes() == 0);
            } finally { release.countDown(); }
        }
    }

    @Test void encodedBulkBudgetRejectsSecondLargeRowBeforeItsCodecRuns() throws Exception {
        var decoded = new AtomicInteger();
        try (var fixture = new Fixture(observing(payload -> decoded.incrementAndGet()), 128L * 1024 * 1024, 5000);
                var tenant = CacheTenantContext.withTenant("a")) {
            // Two-byte characters stay within the codec's 8 Mi-character cap but exceed 16 MiB together.
            fixture.repository.save(new Row(1L, "a", "\u011f".repeat(9 * 512 * 1024)));
            fixture.repository.save(new Row(2L, "a", "\u015f".repeat(9 * 512 * 1024)));
            decoded.set(0);
            assertInstanceOf(IllegalArgumentException.class, assertThrows(ExecutionException.class,
                    () -> fixture.repository.async().findAllAsync(List.of(1L, 2L)).get(10, TimeUnit.SECONDS)).getCause());
            until(() -> fixture.database.nativeReadMemory().deliveries() == 0);
            assertEquals(1, decoded.get(), "Charge before decode; do not decode both then inspect output");
        }
    }

    @Test void causalBulkUsesVersionVerificationAndMutableDuplicatesRemainIndependent() throws Exception {
        try (var fixture = new Fixture(CODEC, 128L * 1024 * 1024, 5000);
                var tenant = CacheTenantContext.withTenant("a")) {
            var expected = new Row(1L, "a", "value");
            var receipt = fixture.repository.saveWithReceipt(expected);
            try (var causal = CacheCausalContext.withReceipt("async_rows", receipt)) {
                var rows = fixture.repository.async().findAllAsync(List.of(1L, 1L)).get(5, TimeUnit.SECONDS);
                assertEquals(List.of(expected, expected), rows);
                assertNotSame(rows.get(0), rows.get(1));
            }
            try (var causal = CacheCausalContext.withMinVersion("async_rows", 1L, receipt.version() + 1)) {
                assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class,
                        () -> fixture.repository.async().findAllAsync(List.of(1L)).get(5, TimeUnit.SECONDS)).getCause());
            }
            until(() -> fixture.database.nativeReadMemory().deliveries() == 0);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String prefix = "t3-" + UUID.randomUUID();
        final JedisPooled redis = new JedisPooled("redis://"
                + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":"
                + Integer.getInteger("cachedb.snapshot.redis.port", 16383));
        final CacheDatabase database;
        final EntityRepository<Row, Long> repository;

        Fixture(EntityCodec<Row> codec, long liveBytes, long timeoutMillis) {
            var sql = new JdbcDataSource();
            sql.setURL("jdbc:h2:mem:" + prefix);
            var config = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .writeBehind(WriteBehindConfig.builder().streamKey(prefix + ":writes")
                            .compactionStreamKey(prefix + ":compact").deadLetterStreamKey(prefix + ":dlq").build())
                    .build();
            database = new CacheDatabase(redis, sql, config, CacheRuntimeOptions.builder()
                    .redisReadCoalescing(RedisReadCoalescingConfig.builder().enabled(true)
                            .maxLiveReplyBytes(liveBytes).timeoutMillis(timeoutMillis).build()).build());
            class EmptySource implements com.reactor.cachedb.core.page.EntityByIdLoader<Row, Long>,
                    com.reactor.cachedb.core.page.EntityOwnershipLoader<Long> {
                public Optional<Row> load(Long id) { return Optional.empty(); }
                public Map<Long, String> loadTenantOwners(Collection<Long> ids) { return Map.of(); }
            }
            database.register(METADATA, codec, CachePolicy.defaults(), null, null, new EmptySource(), null);
            repository = database.repository(METADATA, codec);
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

package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.queue.*;
import com.reactor.cachedb.core.trace.TraceContexts;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.spring.boot.snapshot.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named = "cachedb.snapshot.providers", matches = "true")
class AutomaticSnapshotWriteProvidersTest {
    record Row(String id, String value) { }
    static EntityMetadata<Row, String> metadata(String table) {
        return new EntityMetadata<>() {
            public String entityName() { return "AutoDirtyRow"; }
            public String tableName() { return table; }
            public String redisNamespace() { return table; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row, String> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id", "value_text"); }
            public List<RelationDefinition> relations() { return List.of(); }
            public Map<String, String> columnTypes() { return Map.of("id", "java.lang.String", "value_text", "java.lang.String"); }
        };
    }
    static final EntityCodec<Row> CODEC = new EntityCodec<>() {
        public String toRedisValue(Row row) { return LengthPrefixedPayloadCodec.encode(Map.of("id", row.id, "value_text", row.value)); }
        public Row fromRedisValue(String payload) { var values = LengthPrefixedPayloadCodec.decode(payload); return new Row(values.get("id"), values.get("value_text")); }
        public Map<String, Object> toColumns(Row row) { return Map.of("id", row.id, "value_text", row.value); }
        public Row fromColumns(Map<String, Object> values) { return new Row(values.get("id").toString(), (String) values.get("value_text")); }
    };

    @ParameterizedTest @ValueSource(strings = {"postgres", "mssql", "oracle"})
    void realWritePatchColdPatchAndDeleteDriveSnapshotsOnlyAfterDurability(String provider) throws Exception {
        try (var fixture = new WriteFenceProvidersIntegrationTest.Fixture(provider);
                var redis = new JedisPooled("redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":" + Integer.getInteger("cachedb.snapshot.redis.port", 16383))) {
            String prefix = "auto-" + UUID.randomUUID();
            var metadata = metadata(fixture.table);
            var seen = new CopyOnWriteArrayList<Map<String, String>>();
            var config = CacheDatabaseConfig.builder()
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .adminMonitoring(AdminMonitoringConfig.builder().enabled(false).build())
                    .writeBehind(WriteBehindConfig.builder().streamKey(prefix + ":writes")
                            .dedicatedWriteConsumerGroupEnabled(true)
                            .deadLetterStreamKey(prefix + ":dlq").blockTimeoutMillis(20).retryBackoffMillis(20).build())
                    .writeBehindFlusherFactory((source, registry, settings, performance) -> {
                        var delegate = fixture.flusher();
                        return new WriteBehindFlusher() {
                            public void flush(QueuedWriteOperation operation) throws java.sql.SQLException { seen.add(TraceContexts.current()); delegate.flush(operation); }
                            public void flushBatch(List<QueuedWriteOperation> operations) throws java.sql.SQLException { seen.add(TraceContexts.current()); delegate.flushBatch(operations); }
                            public void flushAtomicBatch(List<QueuedWriteOperation> operations) throws java.sql.SQLException { seen.add(TraceContexts.current()); delegate.flushAtomicBatch(operations); }
                        };
                    }).build();
            try (var database = new CacheDatabase(redis, fixture.source, config)) {
                database.registerJdbcBacked(metadata, CODEC, CachePolicy.defaults());
                var repository = database.repository(metadata, CODEC);
                var source = SnapshotSource.entity("roots", SourceMapping.entity(metadata, CODEC));
                var plan = SnapshotPlan.streaming("automatic", source, Row::id, String.class,
                        row -> List.of(row.value())).scopedByString(source, "id").autoDirtyOnRootEntity();
                var settings = SnapshotSettings.defaults().withDirtyFlush(Duration.ofMillis(250), 1);
                try (var jobs = new SnapshotJobs(fixture.source, redis, new com.fasterxml.jackson.databind.ObjectMapper(),
                        Clock.systemUTC(), prefix, List.of(plan), Map.of("automatic", settings)).withDatabase(database)) {
                    database.start(); jobs.start();
                    WriteReceipt<Row, String> last;
                    try (var scope = TraceContexts.withMetadata(Map.of("correlationId", "stock-command"))) {
                        repository.save(new Row("1", "Şehir"));
                        repository.patchWithReceipt("1", Map.of("value_text", "Önce"));
                        last = repository.patchWithReceipt("1", Map.of("value_text", "Son"));
                    }
                    assertTrue(database.awaitDurable(last, Duration.ofSeconds(10)), database.workerSnapshot().toString());
                    await(() -> jobs.repository("automatic").findById("1").map(value -> value.payload().equals("[\"Son\"]")).orElse(false));
                    assertTrue(seen.stream().anyMatch(value -> "stock-command".equals(value.get("correlationId"))));
                    assertTrue(TraceContexts.current().isEmpty());
                    var keys = new RedisKeyStrategy(prefix);
                    redis.del(keys.entityKey(fixture.table, "1"));
                    var cold = repository.patchWithReceipt("1", Map.of("value_text", "Arşiv"));
                    assertNull(cold.entity());
                    assertTrue(database.awaitDurable(cold, Duration.ofSeconds(10)));
                    await(() -> jobs.repository("automatic").findById("1").map(value -> value.payload().equals("[\"Arşiv\"]")).orElse(false));
                    assertFalse(redis.exists(keys.entityKey(fixture.table, "1")));
                    var deleted = repository.deleteWithReceipt("1");
                    assertTrue(database.awaitDurable(deleted, Duration.ofSeconds(10)));
                    await(() -> jobs.repository("automatic").findById("1").isEmpty());
                    assertThrows(IllegalStateException.class, () -> repository.patchWithReceipt("1", Map.of("value_text", "revived")));
                }
            }
        }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) { if (System.nanoTime() > end) fail("Automatic snapshot did not converge within ten seconds"); Thread.sleep(20); }
    }
}

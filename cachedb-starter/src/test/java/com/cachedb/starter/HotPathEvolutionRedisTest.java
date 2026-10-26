package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.page.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.redis.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class HotPathEvolutionRedisTest {
    static final class Row {
        final Long id; String name; final long score;
        Row(Long id, String name, long score) { this.id = id; this.name = name; this.score = score; }
    }
    static final EntityMetadata<Row, Long> METADATA = new EntityMetadata<>() {
        public String entityName() { return "EvolutionRow"; }
        public String tableName() { return "evolution_rows"; }
        public String redisNamespace() { return "evolution"; }
        public String idColumn() { return "id"; }
        public Class<Row> entityType() { return Row.class; }
        public Function<Row, Long> idAccessor() { return row -> row.id; }
        public List<String> columns() { return List.of("id", "name", "score"); }
        public Set<String> indexedColumns() { return Set.of("id", "score"); }
        public List<RelationDefinition> relations() { return List.of(); }
    };
    static final class Codec implements EntityCodec<Row> {
        final AtomicInteger extractions = new AtomicInteger();
        public String toRedisValue(Row row) { return LengthPrefixedPayloadCodec.encode(Map.of("id", row.id.toString(), "name", row.name, "score", Long.toString(row.score))); }
        public Row fromRedisValue(String value) { var columns = LengthPrefixedPayloadCodec.decode(value); return new Row(Long.valueOf(columns.get("id")), columns.get("name"), Long.parseLong(columns.get("score"))); }
        public Map<String, Object> toColumns(Row row) { extractions.incrementAndGet(); return Map.of("id", row.id, "name", row.name, "score", row.score); }
        public Row fromColumns(Map<String, Object> values) { return new Row(Long.valueOf(values.get("id").toString()), (String) values.get("name"), Long.parseLong(values.get("score").toString())); }
        public boolean directColumnAccess() { return true; }
        public Object entityColumnValue(Row row, String column) { return switch (column) { case "id" -> row.id; case "name" -> row.name; case "score" -> row.score; default -> null; }; }
    }
    static class Source implements VersionedEntitySourceLoader<Row, Long> {
        final AtomicInteger calls = new AtomicInteger();
        public Optional<VersionedEntity<Row>> loadVersionedById(Long id) {
            calls.incrementAndGet();
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return Optional.of(new VersionedEntity<>(new Row(id, "Şehir", 7), 1));
        }
        public List<VersionedEntity<Row>> loadVersionedPage(PageWindow window) { return List.of(); }
        public List<VersionedEntity<Row>> loadVersionedQuery(QuerySpec query) { return List.of(); }
    }
    static final class Fixture implements AutoCloseable {
        final JedisPooled redis = new JedisPooled("redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":" + Integer.getInteger("cachedb.snapshot.redis.port", 16383));
        final Codec codec = new Codec();
        final String prefix;
        final RedisKeyStrategy keys;
        final AtomicInteger reads = new AtomicInteger();
        final CacheDatabase database;
        final EntityRepository<Row, Long> repository;
        Fixture(CachePolicy policy, Source source) {
            this(policy, source, "evolution-" + UUID.randomUUID());
        }
        Fixture(CachePolicy policy, Source source, String prefix) {
            this.prefix = prefix; this.keys = new RedisKeyStrategy(prefix);
            var jdbc = new JdbcDataSource(); jdbc.setURL("jdbc:h2:mem:" + prefix);
            RedisClientOperations client = new RedisClientOperations() {
                public redis.clients.jedis.UnifiedJedis commands() { return redis; }
                public List<String> mget(String... keys) { reads.incrementAndGet(); return redis.mget(keys); }
                public void close() { }
            };
            database = new CacheDatabase(client, jdbc, CacheDatabaseConfig.builder()
                    .runtimeRole(CacheRuntimeRole.CLIENT_ONLY)
                    .keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .writeBehind(WriteBehindConfig.builder().streamKey(prefix + ":writes")
                            .deadLetterStreamKey(prefix + ":deadletters").consumerGroup(prefix).build())
                    .readThrough(ReadThroughConfig.builder().mode(ReadThroughMode.READ_THROUGH_BY_ID).build()).build());
            database.register(METADATA, codec, policy, null, source, source, source);
            repository = database.repository(METADATA, codec, policy);
            database.start();
        }
        public void close() { database.close(); redis.close(); }
    }

    @Test void sixtyFourRepositoryMissesUseOneSourceLoadAndNeverShareMutableEntities() throws Exception {
        var source = new Source();
        try (var fixture = new Fixture(CachePolicy.defaults(), source)) {
            var executor = Executors.newFixedThreadPool(64); var start = new CyclicBarrier(64);
            try {
                var futures = new ArrayList<Future<Row>>();
                for (int i = 0; i < 64; i++) futures.add(executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return fixture.repository.findById(11L).orElseThrow(); }));
                var identities = Collections.newSetFromMap(new IdentityHashMap<Row, Boolean>());
                for (var future : futures) { Row row = future.get(15, TimeUnit.SECONDS); assertEquals("Şehir", row.name); identities.add(row); }
                assertEquals(1, source.calls.get()); assertEquals(64, identities.size());
                identities.iterator().next().name = "mutated";
                assertEquals("Şehir", fixture.repository.findById(11L).orElseThrow().name);
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void sparseBatchExtractsOnceAndResidualQueriesDoNotMaterializeMaps() {
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            var rows = new ArrayList<Row>(); for (long id = 1; id <= 128; id++) rows.add(new Row(id, "name" + id, id));
            fixture.repository.saveAll(rows);
            assertEquals(128, fixture.codec.extractions.get());
            assertEquals(Set.of("id", "score"), fixture.redis.hkeys(fixture.keys.indexMetaKey("evolution", 1L)));
            fixture.codec.extractions.set(0);
            assertEquals(1, fixture.repository.query(QuerySpec.builder().filter(QueryFilter.eq("name", "name7")).limit(10).build()).size());
            assertEquals(0, fixture.codec.extractions.get());
        }
    }

    @Test void patchQueuesOnlyDeltaPreservesUnicodeAndUpdatesAffectedIndexes() {
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            fixture.repository.save(new Row(1L, "Şehir", 7));
            var receipt = fixture.repository.patchWithReceipt(1L, Map.of("score", 9L));
            assertEquals(OperationType.PATCH, receipt.operationType());
            Row row = fixture.repository.findById(1L).orElseThrow();
            assertEquals("Şehir", row.name); assertEquals(9L, row.score);
            assertEquals(9d, fixture.redis.zscore(fixture.keys.indexSortKey("evolution", "score"), "1"));
            fixture.repository.deleteById(1L);
            assertThrows(IllegalStateException.class, () -> fixture.repository.patchWithReceipt(1L, Map.of("score", 10L)));
        }
    }

    @Test void unitOfWorkDiscardsBeforeCommitAndRejectsPrematureReceipts() {
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            assertThrows(IllegalArgumentException.class, () -> fixture.database.inTransaction(tx -> { tx.put(new Row(1L, "a", 1)); throw new IllegalArgumentException("abort"); }));
            assertFalse(fixture.redis.exists(fixture.keys.entityKey("evolution", 1L)));
            assertThrows(IllegalStateException.class, () -> fixture.database.inTransaction(tx -> fixture.repository.saveWithReceipt(new Row(2L, "b", 2))));
            assertFalse(fixture.redis.exists(fixture.keys.entityKey("evolution", 2L)));
            var receipt = fixture.database.inTransaction(tx -> { fixture.repository.save(new Row(1L, "a", 1)); tx.put(new Row(2L, "b", 2)); });
            assertEquals(List.of(1L, 1L), receipt.versions());
            assertTrue(fixture.redis.exists(fixture.keys.entityKey("evolution", 1L)));
            assertTrue(fixture.redis.exists(fixture.keys.entityKey("evolution", 2L)));
        }
    }

    @Test void batchReplayIdentityDoesNotDependOnTraceContext() {
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            CacheWriteBatchReceipt first;
            try (var scope = com.reactor.cachedb.core.trace.TraceContexts.withMetadata(Map.of("correlationId", "first"))) {
                first = fixture.database.writeBatch("retry-same-business-command").put(new Row(1L, "a", 1)).commit();
            }
            try (var scope = com.reactor.cachedb.core.trace.TraceContexts.withMetadata(Map.of("correlationId", "retry"))) {
                assertEquals(first, fixture.database.writeBatch("retry-same-business-command").put(new Row(1L, "a", 1)).commit());
            }
        }
    }

    @Test void nearHitsAvoidRedisAndAWriteInvalidatesAnotherRuntimeBeforeTtlExpiry() throws Exception {
        var policy = CachePolicy.builder().nearCachePolicy(new NearCachePolicy(32, 10, 65536)).build();
        String prefix = "near-runtimes-" + UUID.randomUUID();
        try (var first = new Fixture(policy, new Source(), prefix); var second = new Fixture(policy, new Source(), prefix)) {
            first.repository.save(new Row(1L, "Şehir", 7));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean hit = false;
            while (!hit && System.nanoTime() < deadline) {
                second.repository.findById(1L).orElseThrow();
                int before = second.reads.get();
                Row copy = second.repository.findById(1L).orElseThrow(); copy.name = "local mutation";
                hit = second.reads.get() == before;
                if (!hit) Thread.sleep(5);
            }
            assertTrue(hit, "Subscriber did not become ready for a true zero-Redis L1 hit");
            assertEquals("Şehir", second.repository.findById(1L).orElseThrow().name);
            first.repository.patchWithReceipt(1L, Map.of("name", "İzmir"));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"İzmir".equals(second.repository.findById(1L).orElseThrow().name) && System.nanoTime() < deadline) Thread.sleep(2);
            assertEquals("İzmir", second.repository.findById(1L).orElseThrow().name);
            first.repository.deleteById(1L);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (second.repository.findById(1L).isPresent() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(second.repository.findById(1L).isEmpty());
        }
    }

    @Test void fiveHundredColumnsStoreExactlyThreeIndexMetadataFieldsAndExtractOnce() {
        var extractions = new AtomicInteger();
        var names = new ArrayList<String>(); names.add("id"); for (int i = 1; i < 500; i++) names.add("c" + i);
        EntityMetadata<Map<String, Object>, Long> metadata = new EntityMetadata<>() {
            public String entityName() { return "WideRow"; }
            public String tableName() { return "wide_rows"; }
            public String redisNamespace() { return "wide"; }
            public String idColumn() { return "id"; }
            @SuppressWarnings("unchecked") public Class<Map<String, Object>> entityType() { return (Class<Map<String, Object>>) (Class<?>) Map.class; }
            public Function<Map<String, Object>, Long> idAccessor() { return row -> (Long) row.get("id"); }
            public List<String> columns() { return names; }
            public Set<String> indexedColumns() { return Set.of("id", "c1", "c2"); }
            public List<RelationDefinition> relations() { return List.of(); }
        };
        EntityCodec<Map<String, Object>> codec = new EntityCodec<>() {
            public Map<String, Object> toColumns(Map<String, Object> row) { extractions.incrementAndGet(); return row; }
            public String toRedisValue(Map<String, Object> row) { var strings = new LinkedHashMap<String, String>(); row.forEach((key, value) -> strings.put(key, value.toString())); return LengthPrefixedPayloadCodec.encode(strings); }
            public Map<String, Object> fromRedisValue(String value) { return new LinkedHashMap<>(LengthPrefixedPayloadCodec.decode(value)); }
        };
        String prefix = "wide-" + UUID.randomUUID();
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + prefix);
        try (var redis = new JedisPooled("redis://" + System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1") + ":" + Integer.getInteger("cachedb.snapshot.redis.port", 16383));
                var database = new CacheDatabase(redis, source, CacheDatabaseConfig.builder()
                    .runtimeRole(CacheRuntimeRole.CLIENT_ONLY).keyspace(KeyspaceConfig.builder().keyPrefix(prefix).build())
                    .writeBehind(WriteBehindConfig.builder().streamKey(prefix + ":writes").deadLetterStreamKey(prefix + ":dlq").build())
                    .resourceLimits(ResourceLimits.builder().maxColumnsPerOperation(512).build()).build())) {
            var repository = database.repository(metadata, codec); database.start();
            var rows = new ArrayList<Map<String, Object>>();
            for (long id = 1; id <= 8; id++) { var row = new LinkedHashMap<String, Object>(); row.put("id", id); for (int i = 1; i < 500; i++) row.put("c" + i, "value" + i); rows.add(row); }
            repository.saveAll(rows);
            assertEquals(8, extractions.get());
            for (long id = 1; id <= 8; id++) assertEquals(Set.of("id", "c1", "c2"), redis.hkeys(new RedisKeyStrategy(prefix).indexMetaKey("wide", id)));
        }
    }

    @Test void missingPublishPermissionRejectsBeforeAnyEntityVersionOrStreamMutation() {
        var policy = CachePolicy.builder().nearCachePolicy(new NearCachePolicy(32, 2, 65536)).build();
        try (var fixture = new Fixture(CachePolicy.defaults(), new Source())) {
            String user = "near-denied-" + UUID.randomUUID();
            fixture.redis.sendCommand(redis.clients.jedis.Protocol.Command.ACL, "SETUSER", user, "on", ">fixture-only", "~*", "&*", "+@all", "-publish");
            try (var denied = new JedisPooled(new redis.clients.jedis.HostAndPort(
                    System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1"), Integer.getInteger("cachedb.snapshot.redis.port", 16383)),
                    redis.clients.jedis.DefaultJedisClientConfig.builder().user(user).password("fixture-only").build())) {
                var functions = new RedisFunctionExecutor(denied, RedisFunctionsConfig.defaults(), RedisGuardrailConfig.defaults(),
                        new RedisFunctionArgsMapper().withNearInvalidationChannel(fixture.keys.nearInvalidationChannel()));
                var row = new Row(90L, "safe", 1);
                var operation = new WriteOperation<>(OperationType.UPSERT, METADATA, row.id,
                        fixture.codec.toColumns(row), fixture.codec.toRedisValue(row), 0, java.time.Instant.now());
                var failure = assertThrows(RuntimeException.class, () -> functions.upsert(
                        fixture.keys.entityKey("evolution", row.id), fixture.keys.versionKey("evolution", row.id),
                        fixture.keys.tombstoneKey("evolution", row.id), fixture.prefix + ":denied-stream",
                        fixture.prefix + ":compact-payload", fixture.prefix + ":compact-pending", fixture.prefix + ":compact-stream",
                        fixture.prefix + ":compact-stats", operation, policy, true));
                assertTrue(failure.getMessage().contains("PUBLISH"));
                assertFalse(fixture.redis.exists(fixture.keys.entityKey("evolution", row.id)));
                assertFalse(fixture.redis.exists(fixture.keys.versionKey("evolution", row.id)));
                assertFalse(fixture.redis.exists(fixture.prefix + ":denied-stream"));
            } finally { fixture.redis.sendCommand(redis.clients.jedis.Protocol.Command.ACL, "DELUSER", user); }
        }
    }
}

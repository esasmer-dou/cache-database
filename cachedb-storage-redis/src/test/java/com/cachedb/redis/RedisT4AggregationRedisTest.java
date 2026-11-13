package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisDataException;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisT4AggregationRedisTest {
    record Row(Long id, String tenant, String category, BigDecimal amount, String label) {}

    static final EntityCodec<Row> CODEC = new EntityCodec<>() {
        public String toRedisValue(Row row) {
            return row.id() + "|" + text(row.tenant()) + "|" + text(row.category()) + "|" + text(row.amount()) + "|" + text(row.label());
        }
        public Row fromRedisValue(String raw) {
            var parts = raw.split("\\|", -1);
            return new Row(Long.valueOf(parts[0]), value(parts[1]), value(parts[2]),
                    parts[3].equals("~") ? null : new BigDecimal(parts[3]), value(parts[4]));
        }
        public Map<String, Object> toColumns(Row row) {
            var columns = new LinkedHashMap<String, Object>();
            columns.put("id", row.id()); columns.put("tenant", row.tenant()); columns.put("category", row.category());
            columns.put("amount", row.amount()); columns.put("label", row.label());
            return columns;
        }
        private String text(Object value) { return value == null ? "~" : value.toString(); }
        private String value(String value) { return value.equals("~") ? null : value; }
    };

    static EntityMetadata<Row, Long> metadata(boolean tenantScoped) {
        return new EntityMetadata<>() {
            public String entityName() { return "T4Row"; }
            public String tableName() { return "t4_rows"; }
            public String redisNamespace() { return "t4_rows"; }
            public String idColumn() { return "id"; }
            public Class<Row> entityType() { return Row.class; }
            public Function<Row, Long> idAccessor() { return Row::id; }
            public List<String> columns() { return List.of("id", "tenant", "category", "amount", "label"); }
            public Map<String, String> columnTypes() {
                return Map.of("id", "java.lang.Long", "tenant", "java.lang.String", "category", "java.lang.String",
                        "amount", "java.math.BigDecimal", "label", "java.lang.String");
            }
            public String tenantFieldName() { return tenantScoped ? "tenant" : null; }
            public String extractTenantId(Row row) { return row.tenant(); }
            public List<RelationDefinition> relations() { return List.of(); }
        };
    }

    static final class Client implements RedisClientOperations {
        final JedisPooled redis;
        final boolean lua;
        int aggregates, facets, payloads;
        boolean clusterView;
        Runnable beforePayload;
        Client(JedisPooled redis, boolean lua) { this.redis = redis; this.lua = lua; }
        public UnifiedJedis commands() { return redis; }
        public boolean clustered() { return clusterView; }
        public Optional<IndexAggregateBatch> aggregateIndexBatch(List<String> entities, List<String> tombstones,
                List<String> metadata, List<String> owners, String tenant, String field, boolean count) {
            aggregates++;
            return lua ? RedisClientOperations.super.aggregateIndexBatch(entities, tombstones, metadata, owners, tenant, field, count)
                    : Optional.empty();
        }
        public Optional<FacetIndexBatch> facetIndexBatch(List<String> entities, List<String> tombstones,
                List<String> metadata, List<String> owners, String tenant, String group, String field) {
            facets++;
            return lua ? RedisClientOperations.super.facetIndexBatch(entities, tombstones, metadata, owners, tenant, group, field)
                    : Optional.empty();
        }
        public Optional<List<String>> liveIndexPayloadBatch(List<String> entities, List<String> tombstones,
                List<String> owners, String tenant) {
            payloads++;
            var hook = beforePayload; beforePayload = null;
            if (hook != null) hook.run();
            return RedisClientOperations.super.liveIndexPayloadBatch(entities, tombstones, owners, tenant);
        }
    }

    static final class Fixture implements AutoCloseable {
        final JedisPooled redis;
        final RedisKeyStrategy keys = new RedisKeyStrategy("t4-" + UUID.randomUUID());
        final Set<String> cleanup = new LinkedHashSet<>();
        final EntityMetadata<Row, Long> metadata;
        final RedisQueryIndexManager<Row, Long> indexes;
        Fixture(JedisPooled redis, boolean tenantScoped) {
            this.redis = redis; metadata = metadata(tenantScoped);
            indexes = new RedisQueryIndexManager<>(redis, metadata, CODEC, keys, null,
                    QueryIndexConfig.builder().maxMaterializedCandidateIds(100_001).build(),
                    RelationConfig.defaults(), new QueryEvaluator(), null);
        }
        void seed(List<Row> rows) {
            indexes.reindexBatch(rows);
            cleanup.add(keys.indexAllKey("t4_rows"));
            for (Row row : rows) {
                redis.set(entity(row.id()), CODEC.toRedisValue(row));
                redis.set(owner(row.id()), row.tenant());
                cleanup.add(entity(row.id())); cleanup.add(owner(row.id()));
                cleanup.add(tombstone(row.id())); cleanup.add(keys.indexMetaKey("t4_rows", row.id()));
                for (var column : CODEC.toColumns(row).entrySet()) {
                    String raw = column.getValue() == null ? "__NULL__" : column.getValue().toString();
                    cleanup.add(keys.indexExactKey("t4_rows", column.getKey(), RedisIndexKeySpace.encodeKeyPart(raw)));
                    cleanup.add(keys.indexSortKey("t4_rows", column.getKey()));
                }
            }
        }
        String entity(long id) { return keys.entityKey("t4_rows", id); }
        String owner(long id) { return keys.versionKey("t4_rows", id) + ":tenant"; }
        String tombstone(long id) { return keys.tombstoneKey("t4_rows", id); }
        RedisEntityRepository<Row, Long> repository(Client client, CachePolicy policy) {
            var manager = new RedisQueryIndexManager<>(client, metadata, CODEC, keys, null,
                    QueryIndexConfig.builder().maxMaterializedCandidateIds(100_001).build(),
                    RelationConfig.defaults(), new QueryEvaluator(), null);
            var pages = new RedisPageCacheManager<>(client, metadata, CODEC, policy, keys, null, manager, null,
                    ReadShapeGuardrailConfig.defaults());
            return new RedisEntityRepository<>(client, metadata, CODEC, null, keys, FetchPlan.empty(), policy,
                    null, null, RedisGuardrailConfig.defaults(), "unused:stream", "unused:compaction", 1,
                    null, null, RelationConfig.defaults(), QueryIndexConfig.defaults(), null, PageCacheConfig.defaults(),
                    ReadShapeGuardrailConfig.defaults(), pages, new QueryEvaluator(), manager, null, client, null, null);
        }
        public void close() { for (String key : cleanup) redis.unlink(key); }
    }

    private static Row row(long id, String category, String amount, String label) {
        return new Row(id, "a", category, amount == null ? null : new BigDecimal(amount), label);
    }

    private static List<Row> rows() {
        return List.of(row(1, "x", "0.1", "ok"), row(2, "x", "0.2", null), row(3, "x", null, "__NULL__"),
                row(4, "__NULL__", "7", "literal"), row(5, null, "8", "null-group"),
                row(6, "y", "999999999999999999.125", ""), new Row(7L, "b", "z", new BigDecimal("1000"), "foreign"),
                row(8, "x", "500", "deleted"), row(9, "x", "600", "expired"), row(10, "", "-3", "empty-group"),
                row(11, "\u00c7\u0131\u011f", "2", "unicode"), row(12, "y", "-0.125", "negative"));
    }

    @Test void allAggregateFunctionsAndFacetFunctionsMatchPipelineForTenantAndGlobalReads() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            for (boolean tenantScoped : new boolean[]{false, true}) {
                try (var fixture = new Fixture(redis, tenantScoped);
                        var scope = tenantScoped ? CacheTenantContext.withTenant("a") : CacheTenantContext.administrative()) {
                    fixture.seed(rows()); redis.set(fixture.tombstone(8), "1"); redis.del(fixture.entity(9));
                    // Deliberately poison tenant A's exact index with a foreign-owned entity.
                    redis.sadd(fixture.keys.indexExactKey("t4_rows", "tenant", RedisIndexKeySpace.encodeKeyPart("a")), "7");
                    var fastClient = new Client(redis, true); var slowClient = new Client(redis, false);
                    var fast = fixture.repository(fastClient, CachePolicy.defaults());
                    var slow = fixture.repository(slowClient, CachePolicy.defaults());
                    QueryNode filter = QuerySpec.allOf().rootGroup();
                    assertEquals(tenantScoped ? 9 : 10, fast.aggregate(AggregateSpec.count(filter)).count());
                    for (AggregateFunction function : AggregateFunction.values()) {
                        var spec = new AggregateSpec(function, function == AggregateFunction.COUNT ? null : "amount", filter);
                        assertResult(slow.aggregate(spec), fast.aggregate(spec));
                        assertFacets(slow.aggregateBy("category", spec), fast.aggregateBy("category", spec));
                    }
                    for (String field : List.of("amount", "label")) {
                        var spec = new AggregateSpec(AggregateFunction.COUNT, field, filter);
                        assertResult(slow.aggregate(spec), fast.aggregate(spec));
                        assertFacets(slow.aggregateBy("category", spec), fast.aggregateBy("category", spec));
                    }
                    var facets = fast.aggregateBy("category", AggregateSpec.count(filter));
                    assertTrue(facets.containsKey("__NULL__")); assertTrue(facets.containsKey(""));
                    assertTrue(facets.containsKey("\u00c7\u0131\u011f")); assertFalse(facets.containsKey("null"));
                    assertEquals(1, facets.get("__NULL__").count());
                    assertEquals(tenantScoped ? 8 : 9, fast.aggregate(new AggregateSpec(AggregateFunction.COUNT, "label", filter)).count());
                    assertTrue(fastClient.aggregates > 0 && fastClient.facets > 0 && fastClient.payloads > 0);
                    assertTrue(slowClient.aggregates > 0 && slowClient.facets > 0);
                }
            }
        }
    }

    @Test void countWholeLiveWindowsUsesVectorLivenessAndBoundedOwnerReads() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri()); var fixture = new Fixture(redis, true);
                var scope = CacheTenantContext.withTenant("a")) {
            var rows = new ArrayList<Row>();
            for (int id = 1; id <= 1025; id++) rows.add(row(id, "x", "1", "ok"));
            fixture.seed(rows);
            var fast = fixture.repository(new Client(redis, true), CachePolicy.defaults());
            long beforeExists = commandCalls(redis, "exists"), beforeMget = commandCalls(redis, "mget");
            long beforeGetrange = commandCalls(redis, "getrange");
            assertEquals(1025, fast.aggregate(AggregateSpec.count(null)).count());
            assertTrue(commandCalls(redis, "exists") - beforeExists < 100,
                    "Whole-live COUNT must not dispatch EXISTS per candidate");
            assertEquals(1025, commandCalls(redis, "getrange") - beforeGetrange,
                    "Each owner read is bounded to the tenant length plus one byte");
            assertEquals(0, commandCalls(redis, "mget") - beforeMget,
                    "COUNT must not materialize unbounded owner values");
            redis.set(fixture.owner(4), "foreign");
            redis.set(fixture.owner(5), "a" + "x".repeat(1024 * 1024));
            assertEquals(1023, fast.aggregate(AggregateSpec.count(null)).count());
            redis.set(fixture.tombstone(1), "1");
            redis.del(fixture.owner(1));
            redis.hset(fixture.owner(1), "not", "a string");
            redis.del(fixture.entity(2));
            redis.del(fixture.owner(3));
            assertEquals(1020, fast.aggregate(AggregateSpec.count(null)).count(),
                    "Mixed windows must retain per-row liveness and ownership checks");
        }
    }

    private static long commandCalls(JedisPooled client, String command) {
        Object raw = client.sendCommand(redis.clients.jedis.Protocol.Command.INFO, "commandstats");
        String info = raw instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : raw.toString();
        String prefix = "cmdstat_" + command + ":calls=";
        return info.lines().filter(line -> line.startsWith(prefix)).findFirst()
                .map(line -> Long.parseLong(line.substring(prefix.length(), line.indexOf(',')))).orElse(0L);
    }

    @Test void splitSlotFallbackRechecksPayloadTenantAndPreservesSentinels() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri()); var fixture = new Fixture(redis, true);
                var scope = CacheTenantContext.withTenant("a")) {
            fixture.seed(List.of(row(1, "__NULL__", "0.125", "__NULL__"),
                    new Row(2L, "b", "foreign", new BigDecimal("1000"), "foreign")));
            redis.sadd(fixture.keys.indexExactKey("t4_rows", "tenant", RedisIndexKeySpace.encodeKeyPart("a")), "2");
            var client = new Client(redis, true); client.clusterView = true;
            assertTrue(client.aggregateIndexBatch(List.of(fixture.entity(1)), List.of(fixture.tombstone(1)),
                    List.of(fixture.keys.indexMetaKey("t4_rows", 1)), List.of(fixture.owner(1)), "a", null, true).isEmpty());
            var repository = fixture.repository(client, CachePolicy.defaults());
            var count = AggregateSpec.count(QuerySpec.allOf().rootGroup());
            assertEquals(1, repository.aggregate(count).count());
            assertEquals(1, repository.aggregate(new AggregateSpec(AggregateFunction.COUNT, "label", count.filter())).count());
            assertEquals(Set.of("__NULL__"), repository.aggregateBy("category", count).keySet());
            assertEquals(new BigDecimal("0.125"), repository.aggregate(AggregateSpec.sum("amount", count.filter())).sum());
        }
    }

    @Test void multiple512CandidateBatchesPreserveAllRowsAndDecimals() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri()); var fixture = new Fixture(redis, true);
                var scope = CacheTenantContext.withTenant("a")) {
            var rows = new ArrayList<Row>();
            for (int id = 0; id < 1025; id++) rows.add(row(id, "g" + id % 20, id % 11 == 0 ? null : "0.125", "ok"));
            fixture.seed(rows);
            var fastClient = new Client(redis, true); var slowClient = new Client(redis, false);
            var fast = fixture.repository(fastClient, CachePolicy.defaults());
            var slow = fixture.repository(slowClient, CachePolicy.defaults());
            var spec = AggregateSpec.sum("amount", QuerySpec.allOf().rootGroup());
            var expected = slow.aggregate(spec);
            assertEquals(1025, expected.count()); assertResult(expected, fast.aggregate(spec));
            assertFacets(slow.aggregateBy("category", spec), fast.aggregateBy("category", spec));
            assertEquals(3, fastClient.aggregates); assertEquals(5, fastClient.facets);
        }
    }

    @Test void sentinelPayloadRereadAtomicallyRejectsChangedOwnerTombstoneAndExpiredEntity() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            for (int mutation = 0; mutation < 3; mutation++) {
                try (var fixture = new Fixture(redis, true); var scope = CacheTenantContext.withTenant("a")) {
                    fixture.seed(List.of(row(1, "__NULL__", "1", "__NULL__")));
                    var client = new Client(redis, true);
                    var repository = fixture.repository(client, CachePolicy.defaults());
                    int action = mutation;
                    Runnable change = () -> {
                        if (action == 0) redis.set(fixture.owner(1), "b");
                        else if (action == 1) redis.set(fixture.tombstone(1), "1");
                        else redis.del(fixture.entity(1));
                    };
                    client.beforePayload = change;
                    assertTrue(repository.aggregateBy("category", AggregateSpec.count(QuerySpec.allOf().rootGroup())).isEmpty());
                    redis.set(fixture.owner(1), "a"); redis.del(fixture.tombstone(1));
                    redis.set(fixture.entity(1), CODEC.toRedisValue(row(1, "__NULL__", "1", "__NULL__")));
                    client.beforePayload = change;
                    assertEquals(0, repository.aggregate(new AggregateSpec(AggregateFunction.COUNT, "label",
                            QuerySpec.allOf().rootGroup())).count());
                    assertEquals(2, client.payloads);
                }
            }
        }
    }

    @Test void missingOwnerAndForeignOwnerAreExcludedBeforeReadingIncompleteMetadata() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri()); var fixture = new Fixture(redis, true)) {
            fixture.seed(List.of(row(1, "x", "1", "ok"), row(2, "x", "2", "ok"), row(3, "x", "3", "ok")));
            redis.del(fixture.owner(2)); redis.set(fixture.owner(3), "b");
            redis.del(fixture.keys.indexMetaKey("t4_rows", 2), fixture.keys.indexMetaKey("t4_rows", 3));
            var client = JedisPooledOperations.of(redis);
            var entities = List.of(fixture.entity(1), fixture.entity(2), fixture.entity(3));
            var tombstones = List.of(fixture.tombstone(1), fixture.tombstone(2), fixture.tombstone(3));
            var metadata = List.of(fixture.keys.indexMetaKey("t4_rows", 1), fixture.keys.indexMetaKey("t4_rows", 2),
                    fixture.keys.indexMetaKey("t4_rows", 3));
            var owners = List.of(fixture.owner(1), fixture.owner(2), fixture.owner(3));
            assertEquals(1, client.aggregateIndexBatch(entities, tombstones, metadata, owners, "a", "amount", false).orElseThrow().count());
            assertEquals(List.of(new RedisClientOperations.FacetIndexRow(0, "x", "1")),
                    client.facetIndexBatch(entities, tombstones, metadata, owners, "a", "category", "amount").orElseThrow().rows());
            assertEquals(Arrays.asList(CODEC.toRedisValue(row(1, "x", "1", "ok")), null, null),
                    client.liveIndexPayloadBatch(entities, tombstones, owners, "a").orElseThrow());
        }
    }

    @Test void missingMetadataAndOversizedMetadataFailClosedWhileEmptyRawValuesRemainValid() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri()); var fixture = new Fixture(redis, false)) {
            fixture.seed(List.of(row(1, "", null, "")));
            var client = JedisPooledOperations.of(redis);
            var entities = List.of(fixture.entity(1)); var tombstones = List.of(fixture.tombstone(1));
            String key = fixture.keys.indexMetaKey("t4_rows", 1); var metadata = List.of(key);
            assertEquals("", client.facetIndexBatch(entities, tombstones, metadata, null, null, "category", null)
                    .orElseThrow().rows().get(0).groupRaw());
            redis.hdel(key, "amount");
            assertThrows(JedisDataException.class, () -> client.aggregateIndexBatch(entities, tombstones, metadata, "amount", false));
            assertThrows(JedisDataException.class, () -> client.facetIndexBatch(entities, tombstones, metadata, null, null, "category", "amount"));
            redis.hset(key, "amount", "x".repeat(16 * 1024 * 1024 + 1));
            assertTrue(assertThrows(JedisDataException.class, () -> client.aggregateIndexBatch(
                    entities, tombstones, metadata, "amount", false)).getMessage().contains("byte budget"));
            assertTrue(assertThrows(JedisDataException.class, () -> client.facetIndexBatch(
                    entities, tombstones, metadata, null, null, "category", "amount")).getMessage().contains("byte budget"));
            redis.set(fixture.entity(1), "x".repeat(16 * 1024 * 1024 + 1));
            assertThrows(JedisDataException.class, () -> client.liveIndexPayloadBatch(entities, tombstones, null, null));
        }
    }

    @Test void facetGroupAndCandidateCapsAreNotRelaxed() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri()); var fixture = new Fixture(redis, false)) {
            var rows = new ArrayList<Row>();
            for (int i = 0; i < 257; i++) rows.add(row(i, "g" + i, "1", "ok"));
            fixture.seed(rows);
            var repository = fixture.repository(new Client(redis, true), CachePolicy.defaults());
            assertThrows(IllegalStateException.class, () -> repository.aggregateBy("category", AggregateSpec.count(QuerySpec.allOf().rootGroup())));
            // Bounded SADD batches build the candidate-limit fixture without payloads or scans.
            String all = fixture.keys.indexAllKey("t4_rows");
            for (int start = 257; start <= 100_000; start += 512) {
                var ids = new ArrayList<String>();
                for (int id = start; id < Math.min(100_001, start + 512); id++) ids.add(Integer.toString(id));
                redis.sadd(all, ids.toArray(String[]::new));
            }
            assertTrue(assertThrows(IllegalStateException.class, () -> repository.aggregateBy("category",
                    AggregateSpec.count(QuerySpec.allOf().rootGroup()))).getMessage().contains("candidate limit"));
        }
    }

    @Test void nonCountWindowPolicyAndAmbiguousFiltersDoNotUseNewLuaPaths() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri()); var fixture = new Fixture(redis, false)) {
            fixture.seed(List.of(row(1, "__NULL__", "1", "ok")));
            var client = new Client(redis, true);
            var filtered = fixture.repository(client, CachePolicy.defaults());
            var spec = AggregateSpec.count(QueryFilter.eq("category", "__NULL__"));
            assertEquals(1, filtered.aggregate(spec).count());
            assertEquals(1, filtered.aggregateBy("category", spec).get("__NULL__").count());
            var policy = CachePolicy.builder().customHotPredicate(columns -> true).build();
            var custom = fixture.repository(client, policy);
            assertEquals(1, custom.aggregate(AggregateSpec.count(QuerySpec.allOf().rootGroup())).count());
            assertEquals(1, custom.aggregateBy("category", AggregateSpec.count(QuerySpec.allOf().rootGroup())).size());
            assertEquals(0, client.aggregates); assertEquals(0, client.facets);
        }
    }

    static void assertResult(AggregateResult expected, AggregateResult actual) {
        assertEquals(expected, actual);
        assertEquals(expected.decimalAverage(), actual.decimalAverage(), "DECIMAL128 average must match exactly");
    }

    static void assertFacets(Map<String, AggregateResult> expected, Map<String, AggregateResult> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((group, result) -> assertResult(result, actual.get(group)));
    }
}

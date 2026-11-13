package com.reactor.cachedb.redis;

import static com.reactor.cachedb.redis.RedisCompositeRangeTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisCompositeRangeRedisTest {
    static final Map<String, String> SORTS = Map.of("tenant_status", "score");
    static class Fixture implements AutoCloseable {
        final JedisPooled redis = new JedisPooled(RedisTestEndpoint.uri());
        final String prefix = "f1-" + UUID.randomUUID();
        final RedisKeyStrategy keys = new RedisKeyStrategy(prefix);
        final RedisQueryIndexManager<Row, String> manager;
        final List<String> ids = new ArrayList<>();
        Fixture() { this(false); }
        Fixture(boolean tenant) {
            manager = manager(JedisPooledOperations.of(redis), keys, tenant, SORTS);
            assertTrue(manager.rebuildCompositeIndexesFenced(false, "empty-fixture").degradedCleared());
        }
        void put(Row row) {
            ids.add(row.id());
            redis.set(keys.entityKey("rows", row.id()), CODEC.toRedisValue(row));
            redis.set(keys.versionKey("rows", row.id()) + ":tenant", row.tenant());
            manager.reindex(row);
            assertFalse(redis.exists(keys.indexDegradedKey("rows")), "Reindex must not swallow a failure");
        }
        RedisQueryIndexManager.CompositeWindow<Row> read(QuerySpec query) {
            return manager.resolveCompositeWindow(query, null, row -> true).orElseThrow();
        }
        public void close() {
            String cursor = "0"; int scans = 0;
            do {
                var page = redis.scan(cursor, new redis.clients.jedis.params.ScanParams()
                        .match(prefix + ":*").count(256));
                for (String key : page.getResult()) redis.unlink(key);
                cursor = page.getCursor();
                if (++scans > 10_000) throw new IllegalStateException("Fixture cleanup budget exceeded");
            } while (!cursor.equals("0"));
            redis.close();
        }
    }
    static List<String> ids(RedisQueryIndexManager.CompositeWindow<Row> window) {
        return window.rows().stream().map(row -> row.position().id()).toList();
    }

    @Test void rangesOrderDescOffsetNoOrderAndExactPayloadBoundaryChecks() {
        try (var f = new Fixture()) {
            for (int i = 1; i <= 6; i++) f.put(new Row(Integer.toString(i), "a", "OPEN", i, true));
            assertEquals(List.of("3", "4"), ids(f.read(query(QueryFilter.gt("score", 2), QueryFilter.lt("score", 5)))));
            assertEquals(List.of("2", "3", "4", "5"), ids(f.read(query(QueryFilter.gte("score", 2), QueryFilter.lte("score", 5)))));
            var desc = QuerySpec.allOf(QueryFilter.eq("tenant", "a"), QueryFilter.eq("status", "OPEN"),
                    QueryFilter.gte("score", 2)).orderBy(QuerySort.desc("score")).offsetBy(1).limitTo(2);
            assertEquals(List.of("5", "4"), ids(f.read(desc)));
            assertEquals(List.of("2", "3"), ids(f.read(QuerySpec.allOf(QueryFilter.eq("tenant", "a"),
                    QueryFilter.eq("status", "OPEN"), QueryFilter.gte("score", 2)).limitTo(2))));
            assertTrue(f.read(query(QueryFilter.gt("score", 7), QueryFilter.lt("score", 2))).rows().isEmpty());
        }
    }

    @Test void lexicalTiesContinueAfterDeletedCursorWithInsertsOnBothSides() throws Exception {
        try (var f = new Fixture()) {
            for (String id : List.of("1", "10", "2", "20")) f.put(new Row(id, "a", "OPEN", 5, true));
            var first = f.read(query().limitTo(2));
            assertEquals(List.of("1", "10"), ids(first));
            assertTrue(first.hasMore());
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> {
                    f.put(new Row("09", "a", "OPEN", 5, true)); // Before cursor: intentionally not part of continuation.
                    f.put(new Row("11", "a", "OPEN", 5, true));
                    f.manager.removeById("10"); f.redis.del(f.keys.entityKey("rows", "10"));
                }).get(5, TimeUnit.SECONDS);
            }
            var second = f.manager.resolveCompositeWindow(query().limitTo(10), first.continuation(), row -> true).orElseThrow();
            assertEquals(List.of("11", "2", "20"), ids(second));
            assertFalse(second.hasMore());
            var descending = QuerySpec.allOf(QueryFilter.eq("tenant", "a"), QueryFilter.eq("status", "OPEN"))
                    .orderBy(QuerySort.desc("score")).limitTo(2);
            var head = f.read(descending);
            assertEquals(List.of("20", "2"), ids(head));
            assertEquals(List.of("11", "1"), ids(f.manager.resolveCompositeWindow(descending, head.continuation(), row -> true).orElseThrow()));
            assertEquals(List.of("09"), ids(f.manager.resolveCompositeWindow(descending.limitTo(10),
                    new RedisQueryIndexManager.CompositePosition(5d, "1"), row -> true).orElseThrow()));
        }
    }

    @Test void hugeEqualScoreGroupsUseBoundedSeekAndRedisUtf8MemberOrdering() {
        try (var f = new Fixture()) {
            var rows = new ArrayList<Row>();
            try (var pipeline = f.redis.pipelined()) {
                for (int i = 0; i < 2000; i++) {
                    Row row = new Row(String.format(Locale.ROOT, "%05d", i), "a", "OPEN", 5, true);
                    rows.add(row); f.ids.add(row.id());
                    pipeline.set(f.keys.entityKey("rows", row.id()), CODEC.toRedisValue(row));
                }
                pipeline.sync();
            }
            f.manager.reindexBatch(rows);
            var page = f.manager.resolveCompositeWindow(query().limitTo(2),
                    new RedisQueryIndexManager.CompositePosition(5, "01995"), row -> true).orElseThrow();
            assertEquals(List.of("01996", "01997"), ids(page));
            assertEquals(1, page.rounds()); assertTrue(page.examinedIds() <= 64);
            f.put(new Row("\uE000", "a", "OPEN", 6, true));
            f.put(new Row("\uD800\uDC00", "a", "OPEN", 6, true));
            assertEquals(List.of("\uE000", "\uD800\uDC00"), ids(f.read(query(QueryFilter.gt("score", 5)))));
        }
    }

    @Test void tupleAndScoreMovesDeletesRebuildAndLegacyUpgradePreserveCorrectness() {
        try (var f = new Fixture()) {
            f.put(new Row("1", "a", "OPEN", 10, true));
            String original = exact(f.keys) + ":sort";
            f.put(new Row("1", "b", "OPEN", 20, true));
            assertNull(f.redis.zscore(original, "1"));
            f.put(new Row("1", "a", "OPEN", 30, true));
            assertEquals(30d, f.redis.zscore(original, "1"));
            assertEquals(List.of("1"), ids(f.read(query())));
            f.manager.removeById("1"); f.redis.del(f.keys.entityKey("rows", "1"));
            assertNull(f.redis.zscore(original, "1"));
            f.put(new Row("2", "a", "OPEN", 40, true));
            assertTrue(f.manager.rebuildCompositeIndexesFenced(false, "f1-test").degradedCleared());
            assertEquals(List.of("2"), ids(f.read(query())));
            assertEquals(40d, f.redis.zscore(original, "2"));
        }
        try (var f = new Fixture()) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            f.redis.set(f.keys.compositeIndexSignatureKey("rows"), "v1|tenant_status=tenant,status");
            var legacy = manager(JedisPooledOperations.of(f.redis), f.keys, false, Map.of());
            assertEquals(RedisQueryIndexManager.CompositeIndexState.VERIFIED, legacy.compositeIndexState());
            var upgraded = manager(JedisPooledOperations.of(f.redis), f.keys, false, SORTS);
            assertEquals(RedisQueryIndexManager.CompositeIndexState.PENDING_REBUILD, upgraded.compositeIndexState());
            assertTrue(upgraded.resolveCompositeWindow(query(), null, row -> true).isEmpty());
            assertEquals(List.of("1"), upgraded.resolveCandidateIds(query()));
            upgraded.rebuildCompositeIndexesFenced(false, "enable-sort");
            assertTrue(upgraded.resolveCompositeWindow(query(), null, row -> true).isPresent());
        }
    }

    @Test void tombstoneExpiryResidualAndAdmissionFilteringRefillWithoutDoubleOffset() {
        try (var f = new Fixture()) {
            for (int i = 1; i <= 130; i++) f.put(new Row(Integer.toString(i), "a", "OPEN", i, i > 64));
            f.redis.del(f.keys.entityKey("rows", "65"));
            f.redis.set(f.keys.tombstoneKey("rows", "66"), "1");
            var page = f.manager.resolveCompositeWindow(query(QueryFilter.eq("selected", true)).offsetBy(1).limitTo(2),
                    null, row -> row.score() > 67).orElseThrow();
            assertEquals(List.of("69", "70"), ids(page)); assertEquals(2, page.rounds());
        }
    }

    @Test void fourRoundsAndTenThousandIdsReturnFallbackNotPartialPages() {
        try (var f = new Fixture()) {
            var rows = new ArrayList<Row>();
            for (int i = 0; i < 10_001; i++) rows.add(new Row(Integer.toString(i), "a", "OPEN", i, true));
            f.manager.reindexBatch(rows.subList(0, 10_000));
            f.manager.reindex(rows.get(10_000));
            AtomicInteger ranges = new AtomicInteger();
            RedisClientOperations counted = new RedisClientOperations() {
                public UnifiedJedis commands() { return f.redis; }
                public Object eval(String script, List<String> keys, List<String> args) {
                    if (script.contains("local probes=0")) ranges.incrementAndGet();
                    return RedisClientOperations.super.eval(script, keys, args);
                }
            };
            var reader = manager(counted, f.keys, false, SORTS);
            assertTrue(reader.resolveCompositeWindow(query().limitTo(6000), null, row -> true).isEmpty());
            assertEquals(4, ranges.get());
            assertEquals(1, reader.compositeBoundedFallbacks());
            // No payloads exist: a short page would be silently incomplete, not success.
            assertThrows(IllegalStateException.class, () -> reader.resolveCompositeWindowFallback(query(), null, row -> true));
            ranges.set(0);
            assertTrue(reader.resolveCompositeWindow(query().offsetBy(Integer.MAX_VALUE), null, row -> true).isEmpty());
            assertEquals(0, ranges.get());
        }
    }

    @Test void tenantOwnerInjectionCausalAndNullScoreAreFailClosed() {
        try (var f = new Fixture(true); var scope = CacheTenantContext.withTenant("a")) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            f.put(new Row("2", "a", "OPEN", 2, true));
            f.redis.set(f.keys.versionKey("rows", "2") + ":tenant", "foreign");
            assertEquals(List.of("1"), ids(f.read(query())));
            try (var causal = CacheCausalContext.withMinVersion("rows", "1", 10)) {
                assertTrue(f.manager.resolveCompositeWindow(query(), null, row -> true).isEmpty());
            }
            f.put(new Row("3", "a", "OPEN", null, true));
            assertTrue(f.manager.resolveCompositeWindow(query(), null, row -> true).isEmpty());
        }
    }

    @Test void missingMetadataDegradesAndRebuildRetainsColonSeparatedIds() {
        try (var f = new Fixture()) {
            f.put(new Row("order:1", "a", "OPEN", 1, true));
            f.manager.rebuildCompositeIndexesFenced(false, "colon-id");
            assertEquals(List.of("order:1"), ids(f.read(query())));
            f.redis.del(f.keys.indexMetaKey("rows", "order:1"));
            f.manager.removeById("order:1");
            assertTrue(f.redis.exists(f.keys.indexDegradedKey("rows")));
            assertTrue(f.manager.resolveCompositeWindow(query(), null, row -> true).isEmpty());
        }
    }

    @Test void exhaustedRefillUsesCompletePayloadBasedFallbackWithTheSameCursorOrder() {
        try (var f = new Fixture()) {
            for (int i = 0; i < 300; i++) f.put(new Row(Integer.toString(i), "a", "OPEN", i, i == 299));
            QuerySpec filtered = query(QueryFilter.eq("selected", true)).limitTo(1);
            assertTrue(f.manager.resolveCompositeWindow(filtered, null, row -> true).isEmpty());
            assertEquals(List.of("299"), ids(f.manager.resolveCompositeWindowFallback(filtered, null, row -> true)));
            f.put(new Row("10", "a", "OPEN", 500, true));
            f.put(new Row("2", "a", "OPEN", 500, true));
            assertEquals(List.of("2"), ids(f.manager.resolveCompositeWindowFallback(
                    query(QueryFilter.gte("score", 500)), new RedisQueryIndexManager.CompositePosition(500, "10"), row -> true)));
        }
    }

    @Test void atomicBatchDegradationDisablesSortUntilExplicitRebuild() {
        try (var f = new Fixture()) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            var registry = new DefaultEntityRegistry(ResourceLimits.defaults());
            registry.register(metadata(false, SORTS), CODEC, CachePolicy.defaults(), null, null);
            var config = WriteBehindConfig.builder().streamKey("f1-stream-" + UUID.randomUUID()).build();
            try {
                new RedisAtomicWriteBatch(f.redis, registry, f.keys, config, RedisGuardrailConfig.defaults(), null, "f1-batch")
                        .put(new Row("1", "a", "OPEN", 9, true)).commit();
                assertTrue(f.redis.exists(f.keys.indexDegradedKey("rows")));
                assertTrue(f.manager.resolveCompositeWindow(query(), null, row -> true).isEmpty());
                f.manager.rebuildCompositeIndexesFenced(false, "post-batch");
                assertEquals(9d, f.read(query()).rows().get(0).position().score());
            } finally {
                String stream = f.keys.writeStreamKey(config.activeStreamKey(), "rows");
                f.redis.del(stream, stream + ":batch:f1-batch");
            }
        }
    }
}

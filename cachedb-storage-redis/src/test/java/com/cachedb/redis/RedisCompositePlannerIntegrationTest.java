package com.reactor.cachedb.redis;

import static com.reactor.cachedb.redis.RedisCompositeRangeTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.page.EntityQueryLoader;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.relation.RelationBatchLoader;
import com.reactor.cachedb.core.repository.*;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
class RedisCompositePlannerIntegrationTest {
    static <T> RedisEntityRepository<T, String> repository(RedisClientOperations client,
            EntityMetadata<T, String> metadata, EntityCodec<T> codec, RedisKeyStrategy keys,
            RedisQueryIndexManager<T, String> manager, CachePolicy policy, EntityQueryLoader<T> source,
            ReadThroughConfig read, RelationBatchLoader<T> relations) {
        var shape = ReadShapeGuardrailConfig.defaults();
        var pages = new RedisPageCacheManager<>(client, metadata, codec, policy, keys,
                new RedisHotSetManager(client, keys), manager, null, shape, null);
        return new RedisEntityRepository<>(client, metadata, codec, null, keys, FetchPlan.empty(), policy,
                null, null, RedisGuardrailConfig.defaults(), "unused", "unused-compact", 1,
                null, relations, RelationConfig.defaults(), QueryIndexConfig.defaults(),
                null, null, source, PageCacheConfig.defaults(), read, shape, pages,
                new QueryEvaluator(), manager, null, client, null, null);
    }
    private static CachePolicy policy(EntityHotPolicy hot) {
        return CachePolicy.builder().hotEntityLimit(CachePolicy.UNBOUNDED).lruEvictionEnabled(false).hotPolicy(hot).build();
    }
    private static RedisEntityRepository<Row, String> repository(RedisCompositeRangeRedisTest.Fixture f,
            CachePolicy policy, EntityQueryLoader<Row> source, ReadThroughConfig read, RelationBatchLoader<Row> relations) {
        return repository(JedisPooledOperations.of(f.redis), metadata(false, RedisCompositeRangeRedisTest.SORTS), CODEC,
                f.keys, f.manager, policy, source, read, relations);
    }
    private static List<String> rowIds(CursorPage<Row> page) { return page.items().stream().map(Row::id).toList(); }

    @Test void ordinarySingleScoreQueryUsesFastWindowWithoutDoubleOffset() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            for (int i = 0; i < 200; i++) f.put(new Row(Integer.toString(i), "a", "OPEN", i, true));
            var ranges = new AtomicInteger();
            RedisClientOperations counted = new RedisClientOperations() {
                public redis.clients.jedis.UnifiedJedis commands() { return f.redis; }
                public Object eval(String script, List<String> keys, List<String> args) {
                    if (script.contains("local probes=0")) ranges.incrementAndGet();
                    return RedisClientOperations.super.eval(script, keys, args);
                }
            };
            var manager = manager(counted, f.keys, false, RedisCompositeRangeRedisTest.SORTS);
            var repo = repository(counted, metadata(false, RedisCompositeRangeRedisTest.SORTS), CODEC, f.keys, manager,
                    policy(EntityHotPolicy.countWindow()), null, ReadThroughConfig.defaults(), (r, c) -> {});
            assertEquals(List.of("11", "12"), repo.query(query(QueryFilter.gt("score", 9)).offsetBy(1).limitTo(2))
                    .stream().map(Row::id).toList());
            assertEquals(1, ranges.get());
        }
    }

    @Test void missingSignatureNeverClaimsAnEmptyUntrackedNamespaceIsVerified() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            f.redis.set(f.keys.entityKey("rows", "untracked"), CODEC.toRedisValue(new Row("untracked", "a", "OPEN", 3, true)));
            f.redis.del(f.keys.compositeIndexSignatureKey("rows"), f.keys.indexAllKey("rows"), f.keys.hotSetKey("rows"));
            var reader = manager(JedisPooledOperations.of(f.redis), f.keys, false, RedisCompositeRangeRedisTest.SORTS);
            assertEquals(RedisQueryIndexManager.CompositeIndexState.PENDING_REBUILD, reader.compositeIndexState());
            assertTrue(reader.resolveCompositeWindow(query(), null, row -> true).isEmpty());
            assertFalse(reader.rebuildFromEntityStore(false, "not-fenced").degradedCleared());
            assertTrue(reader.rebuildCompositeIndexesFenced(false, "recover-untracked").degradedCleared());
            assertEquals(List.of("untracked"), reader.resolveCompositeWindow(query(), null, row -> true).orElseThrow()
                    .rows().stream().map(row -> row.position().id()).toList());
        }
    }

    @Test void purePointQueryAndRecencyTouchesNeverRegisterCompositeWriters() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            var registrations = new AtomicInteger();
            RedisClientOperations counted = new RedisClientOperations() {
                public redis.clients.jedis.UnifiedJedis commands() { return f.redis; }
                public Object eval(String script, List<String> keys, List<String> args) {
                    if (script.contains("Composite active-writer bound exceeded")) registrations.incrementAndGet();
                    return RedisClientOperations.super.eval(script, keys, args);
                }
            };
            var manager = manager(counted, f.keys, false, RedisCompositeRangeRedisTest.SORTS);
            var repo = repository(counted, metadata(false, RedisCompositeRangeRedisTest.SORTS), CODEC, f.keys, manager,
                    policy(EntityHotPolicy.countWindow()), null, ReadThroughConfig.defaults(), (r, c) -> {});
            assertTrue(repo.findById("1").isPresent());
            assertEquals(List.of("1"), repo.query(query()).stream().map(Row::id).toList());
            assertEquals(List.of("1"), rowIds(repo.queryRedisLexicalWindow(query(), WindowRequest.first(1),
                    "tenant_status", "read-only", "a", null, null)));
            var pages = new RedisPageCacheManager<>(counted, metadata(false, RedisCompositeRangeRedisTest.SORTS), CODEC,
                    policy(EntityHotPolicy.countWindow()), f.keys, new RedisHotSetManager(counted, f.keys), manager,
                    null, ReadShapeGuardrailConfig.defaults(), null);
            pages.recordEntityAccessBatch(List.of("1"));
            pages.recordEntityAccessBatch(Map.of("1", CODEC.toColumns(new Row("1", "a", "OPEN", 1, true))), Map.of("1", 100L));
            try (var migration = new RedisCompositeMigrationFence(counted, f.keys, "rows").exclusive()) {
                pages.recordEntityAccess("1", Map.of(), 0L);
                assertTrue(repo.findById("1").isPresent());
            }
            assertEquals(0, registrations.get());
        }
    }

    @Test void actualHotEvictionStillRegistersBeforePayloadAndIndexRemoval() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            f.put(new Row("old", "a", "OPEN", 1, true)); f.put(new Row("new", "a", "OPEN", 2, true));
            f.redis.zadd(f.keys.hotSetKey("rows"), 0, "old");
            var registrations = new AtomicInteger();
            RedisClientOperations counted = new RedisClientOperations() {
                public redis.clients.jedis.UnifiedJedis commands() { return f.redis; }
                public Object eval(String script, List<String> keys, List<String> args) {
                    if (script.contains("Composite active-writer bound exceeded")) registrations.incrementAndGet();
                    return RedisClientOperations.super.eval(script, keys, args);
                }
            };
            var manager = manager(counted, f.keys, false, RedisCompositeRangeRedisTest.SORTS);
            var repo = repository(counted, metadata(false, RedisCompositeRangeRedisTest.SORTS), CODEC, f.keys, manager,
                    CachePolicy.builder().hotEntityLimit(1).lruEvictionEnabled(true).build(),
                    null, ReadThroughConfig.defaults(), (r, c) -> {});
            assertTrue(repo.findById("new").isPresent());
            assertEquals(1, registrations.get());
            assertFalse(f.redis.exists(f.keys.entityKey("rows", "old")));
            assertNull(f.redis.zscore(exact(f.keys) + ":sort", "old"));
        }
    }

    @Test void causalRequirementsForAnotherNamespaceDoNotRejectThisOptInQuery() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture();
                var unrelated = CacheCausalContext.withMinVersion("another-namespace", "1", 10)) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            var repo = repository(f, policy(EntityHotPolicy.countWindow()), null, ReadThroughConfig.defaults(), (r, c) -> {});
            assertEquals(List.of("1"), rowIds(repo.queryRedisLexicalWindow(query(), WindowRequest.first(1),
                    "tenant_status", "unrelated-causal", "a", null, null)));
        }
    }

    @Test void actualRepositoryUsesLexicalTiesAndVersionedLastReturnedCursor() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            for (String id : List.of("1", "10", "2", "20")) f.put(new Row(id, "a", "OPEN", 5, true));
            var repo = repository(f, policy(EntityHotPolicy.countWindow()), null, ReadThroughConfig.defaults(), (rows, context) -> {});
            var first = repo.queryRedisLexicalWindow(query(), WindowRequest.first(2), "tenant_status", "route", "a", null, null);
            assertEquals(List.of("1", "10"), rowIds(first));
            assertNotNull(first.nextCursor());
            f.manager.removeById("10"); f.redis.del(f.keys.entityKey("rows", "10"));
            f.put(new Row("09", "a", "OPEN", 5, true));
            f.put(new Row("11", "a", "OPEN", 5, true));
            var next = repo.queryRedisLexicalWindow(query(), WindowRequest.after(first.nextCursor(), 10),
                    "tenant_status", "route", "a", null, null);
            assertEquals(List.of("11", "2", "20"), rowIds(next));
            assertNull(next.nextCursor());
            assertThrows(CursorContractMismatchException.class, () -> repo.queryRedisLexicalWindow(
                    query(), WindowRequest.after(first.nextCursor(), 2), "tenant_status", "another-route", "a", null, null));
            assertThrows(CursorContractMismatchException.class, () -> repo.queryRedisLexicalWindow(
                    query(QueryFilter.gt("score", 1)), WindowRequest.after(first.nextCursor(), 2), "tenant_status", "route", "a", null, null));
            assertThrows(IllegalArgumentException.class, () -> repo.queryRedisLexicalWindow(
                    query(), WindowRequest.after(WindowCursor.encode(Map.of("score", 5, "id", "10"), "route"), 2),
                    "tenant_status", "route", "a", null, null));
            assertThrows(IllegalArgumentException.class, () -> repo.queryRedisLexicalWindow(
                    query(), WindowRequest.after(first.nextCursor(), 2), "tenant_status", "route", "a", 5d, "10"));
        }
    }

    @Test void actualPolicyResidualRefillFallbackAndFetchPlanArePreserved() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            for (int i = 0; i < 301; i++) f.put(new Row(Integer.toString(i), "a", "OPEN", i, i >= 299));
            f.redis.set(f.keys.tombstoneKey("rows", "299"), "1");
            var hot = EntityHotPolicy.builder().mode(EntityHotPolicyMode.CUSTOM_PREDICATE)
                    .customPredicate(columns -> Boolean.TRUE.equals(columns.get("selected")))
                    .evictWhenRejected(false).build();
            var fetches = new AtomicInteger();
            var repo = repository(f, policy(hot), null, ReadThroughConfig.defaults(), (rows, context) -> {
                assertEquals(List.of("300"), rows.stream().map(Row::id).toList());
                fetches.incrementAndGet();
            });
            var page = repo.queryRedisLexicalWindow(query().fetching(FetchPlan.of("children")), WindowRequest.first(1),
                    "tenant_status", "policy", "a", null, null);
            assertEquals(List.of("300"), rowIds(page));
            assertNull(page.nextCursor());
            assertEquals(1, fetches.get());
            assertTrue(f.manager.compositeBoundedFallbacks() >= 1);
        }
    }

    @Test void tenantOwnerAndCausalScopesCannotBecomeUnverifiedResults() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture(true); var tenant = CacheTenantContext.withTenant("a")) {
            f.put(new Row("1", "a", "OPEN", 1, true));
            f.put(new Row("2", "a", "OPEN", 2, true));
            f.redis.set(f.keys.versionKey("rows", "2") + ":tenant", "b");
            var repo = repository(JedisPooledOperations.of(f.redis), metadata(true, RedisCompositeRangeRedisTest.SORTS),
                    CODEC, f.keys, f.manager, policy(EntityHotPolicy.countWindow()), null, ReadThroughConfig.defaults(), (r, c) -> {});
            assertEquals(List.of("1"), rowIds(repo.queryRedisLexicalWindow(query(), WindowRequest.first(10),
                    "tenant_status", "tenant", "a", null, null)));
            assertTrue(f.redis.exists(f.keys.entityKey("rows", "2")), "Foreign ownership must not trigger removal");
            try (var causal = CacheCausalContext.withMinVersion("rows", "missing", 2)) {
                var lexicalFailure = assertThrows(IllegalStateException.class, () -> repo.queryRedisLexicalWindow(query(), WindowRequest.first(1),
                        "tenant_status", "tenant", "a", null, null));
                assertTrue(lexicalFailure.getMessage().contains("causal"), lexicalFailure.getMessage());
                var queryFailure = assertThrows(IllegalStateException.class, () -> repo.query(query()));
                assertTrue(queryFailure.getMessage().contains("causal"), queryFailure.getMessage());
            }
        }
    }

    @Test void sourceFallbackMaterializesBeforeLexicalSortAndRefusesUnprovenCompleteness() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            var calls = new AtomicInteger();
            List<Row> source = List.of(new Row("2", "a", "OPEN", 5, true),
                    new Row("10", "a", "OPEN", 5, true), new Row("1", "a", "OPEN", 5, true));
            var read = ReadThroughConfig.builder().mode(ReadThroughMode.READ_THROUGH_QUERY)
                    .hydrateLoadedEntities(false).maxQueryLoadRows(4).build();
            var repo = repository(f, policy(EntityHotPolicy.countWindow()), spec -> {
                calls.incrementAndGet(); assertEquals(4, spec.limit()); assertTrue(spec.sorts().isEmpty()); return source;
            }, read, (r, c) -> {});
            var page = repo.queryRedisLexicalWindow(query(), WindowRequest.first(2),
                    "tenant_status", "source", "a", null, null);
            assertEquals(List.of("1", "10"), rowIds(page));
            assertNotNull(page.nextCursor()); assertEquals(1, calls.get());
            var tooSmall = repository(f, policy(EntityHotPolicy.countWindow()), spec -> source,
                    ReadThroughConfig.builder().mode(ReadThroughMode.READ_THROUGH_QUERY)
                            .hydrateLoadedEntities(false).maxQueryLoadRows(3).build(), (r, c) -> {});
            assertThrows(IllegalStateException.class, () -> tooSmall.queryRedisLexicalWindow(query(),
                    WindowRequest.first(2), "tenant_status", "source", "a", null, null));
        }
    }

    @Test void missingIndexedPayloadUsesSourceRatherThanSilentlyReturningAShortPage() {
        try (var f = new RedisCompositeRangeRedisTest.Fixture()) {
            Row row = new Row("1", "a", "OPEN", 3, true); f.put(row);
            f.redis.del(f.keys.entityKey("rows", "1"));
            var calls = new AtomicInteger();
            var repo = repository(f, policy(EntityHotPolicy.countWindow()), spec -> {
                calls.incrementAndGet(); return List.of(row);
            }, ReadThroughConfig.builder().mode(ReadThroughMode.READ_THROUGH_QUERY)
                    .hydrateLoadedEntities(false).maxQueryLoadRows(4).build(), (r, c) -> {});
            assertEquals(List.of("1"), rowIds(repo.queryRedisLexicalWindow(query(), WindowRequest.first(2),
                    "tenant_status", "source", "a", null, null)));
            assertEquals(1, calls.get());
        }
    }

    record TypedRow(String id, Object score) {}
    static final class TypedFixture implements AutoCloseable {
        final JedisPooled redis = new JedisPooled(RedisTestEndpoint.uri());
        final String prefix = "f1-logical-" + UUID.randomUUID();
        final RedisKeyStrategy keys = new RedisKeyStrategy(prefix);
        final EntityMetadata<TypedRow, String> metadata;
        final EntityCodec<TypedRow> codec;
        final RedisQueryIndexManager<TypedRow, String> manager;
        final RedisEntityRepository<TypedRow, String> repo;
        TypedFixture(String type, Function<String, Object> parser) {
            metadata = new EntityMetadata<>() {
                public String entityName() { return "TypedRow"; }
                public String tableName() { return "rows"; }
                public String redisNamespace() { return "rows"; }
                public String idColumn() { return "id"; }
                public Class<TypedRow> entityType() { return TypedRow.class; }
                public Function<TypedRow, String> idAccessor() { return TypedRow::id; }
                public List<String> columns() { return List.of("id", "status", "score"); }
                public Map<String, String> columnTypes() { return Map.of("id", "java.lang.String", "status", "java.lang.String", "score", type); }
                public List<RelationDefinition> relations() { return List.of(); }
                public Map<String, List<String>> compositeExactIndexes() { return Map.of("status_score", List.of("status")); }
                public Map<String, String> compositeSortColumns() { return Map.of("status_score", "score"); }
            };
            codec = new EntityCodec<>() {
                public String toRedisValue(TypedRow row) { return row.id() + "|" + row.score(); }
                public TypedRow fromRedisValue(String value) {
                    int split = value.indexOf('|'); return new TypedRow(value.substring(0, split), parser.apply(value.substring(split + 1)));
                }
                public Map<String, Object> toColumns(TypedRow row) { return Map.of("id", row.id(), "status", "OPEN", "score", row.score()); }
            };
            var client = JedisPooledOperations.of(redis);
            manager = new RedisQueryIndexManager<>(client, metadata, codec, keys, null, QueryIndexConfig.defaults(),
                    RelationConfig.defaults(), new QueryEvaluator(), null);
            assertTrue(manager.rebuildCompositeIndexesFenced(false, "empty-typed-fixture").degradedCleared());
            repo = repository(client, metadata, codec, keys, manager, policy(EntityHotPolicy.countWindow()),
                    null, ReadThroughConfig.defaults(), (r, c) -> {});
        }
        void put(String id, Object score) {
            var row = new TypedRow(id, score);
            redis.set(keys.entityKey("rows", id), codec.toRedisValue(row)); manager.reindex(row);
            assertFalse(redis.exists(keys.indexDegradedKey("rows")));
        }
        QuerySpec query(QueryFilter... extra) {
            QuerySpec query = QuerySpec.allOf(QueryFilter.eq("status", "OPEN"));
            for (var filter : extra) query = query.and(filter);
            return query.orderBy(QuerySort.asc("score"), QuerySort.asc("id")).limitTo(10);
        }
        public void close() {
            String cursor = "0"; int scans = 0;
            do {
                var page = redis.scan(cursor, new redis.clients.jedis.params.ScanParams()
                        .match(prefix + ":*").count(256));
                page.getResult().forEach(redis::unlink); cursor = page.getCursor();
                if (++scans > 10_000) throw new IllegalStateException("Cleanup scan limit exceeded");
            } while (!"0".equals(cursor));
            redis.close();
        }
    }

    @Test void longValuesAboveExactDoubleDomainKeepLogicalOrderAndExclusiveRange() {
        try (var f = new TypedFixture("java.lang.Long", Long::valueOf)) {
            long low = 9_007_199_254_740_992L, high = low + 1;
            f.put("z", low); f.put("a", high);
            assertEquals(List.of("z", "a"), f.repo.query(f.query()).stream().map(TypedRow::id).toList());
            assertEquals(List.of("a"), f.repo.query(f.query(QueryFilter.gt("score", low))).stream().map(TypedRow::id).toList());
            assertEquals(List.of("z"), f.repo.query(f.query(QueryFilter.lt("score", high))).stream().map(TypedRow::id).toList());
            QuerySpec typedSeek = KeysetPagination.apply(f.query(), WindowRequest.after(
                    WindowCursor.encode(Map.of("score", low, "id", "z")), 10),
                    List.of(QuerySort.asc("score"), QuerySort.asc("id")));
            assertEquals(List.of("a"), f.repo.query(typedSeek).stream().map(TypedRow::id).toList());
            assertThrows(IllegalArgumentException.class, () -> f.repo.queryRedisLexicalWindow(f.query(),
                    WindowRequest.first(1), "status_score", "unsafe", null, null, null));
        }
    }

    @Test void nanosOffsetsAndNegativeZeroRetainLogicalComparatorInsteadOfRedisScores() {
        assertLogicalPair("java.time.Instant", Instant::parse, Instant.parse("2026-01-01T00:00:00.000000001Z"),
                Instant.parse("2026-01-01T00:00:00.000000002Z"));
        assertLogicalPair("java.time.LocalDateTime", LocalDateTime::parse, LocalDateTime.parse("2026-01-01T00:00:00.000000001"),
                LocalDateTime.parse("2026-01-01T00:00:00.000000002"));
        assertLogicalPair("java.time.OffsetDateTime", OffsetDateTime::parse, OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-01-01T01:00:00+01:00"));
        assertLogicalPair("java.lang.Double", Double::valueOf, -0d, 0d);
    }
    private void assertLogicalPair(String type, Function<String, Object> parser, Object low, Object high) {
        try (var f = new TypedFixture(type, parser)) {
            f.put("z", low); f.put("a", high);
            assertEquals(List.of("z", "a"), f.repo.query(f.query()).stream().map(TypedRow::id).toList(), type);
            assertEquals(List.of("a"), f.repo.query(f.query(QueryFilter.gt("score", low))).stream().map(TypedRow::id).toList(), type);
        }
    }
}

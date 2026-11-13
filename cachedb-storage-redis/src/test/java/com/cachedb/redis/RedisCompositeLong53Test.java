package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import static com.reactor.cachedb.redis.RedisCompositePlannerIntegrationTest.*;
import com.reactor.cachedb.core.cache.*;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.queue.WriteBehindQueue;
import com.reactor.cachedb.core.model.WriteOperation;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis", matches="true")
class RedisCompositeLong53Test {
    static String flag(TypedFixture f) { return f.keys.compositeIndexSignatureKey("rows") + ":long53:status_score"; }
    static QuerySpec query(TypedFixture f, QueryFilter... filters) {
        QuerySpec query = QuerySpec.allOf(QueryFilter.eq("status", "OPEN"));
        for (var filter : filters) query = query.and(filter);
        return query.orderBy(QuerySort.asc("score")).limitTo(10);
    }
    static void put(TypedFixture f, String id, long score) { put(f, f.manager, id, score); }
    static void put(TypedFixture f, RedisQueryIndexManager<TypedRow, String> manager, String id, long score) {
        var row = new TypedRow(id, score);
        manager.withCompositeMutation(() -> {
            manager.validateCompositeValues(f.codec.toColumns(row));
            f.redis.set(f.keys.entityKey("rows", id), f.codec.toRedisValue(row));
            manager.reindex(row); return null;
        });
    }
    static RedisQueryIndexManager<TypedRow, String> manager(TypedFixture f, RedisClientOperations client) {
        return new RedisQueryIndexManager<>(client, f.metadata, f.codec, f.keys, null, QueryIndexConfig.defaults(),
                RelationConfig.defaults(), new QueryEvaluator(), null);
    }
    static RedisEntityRepository<TypedRow, String> writer(TypedFixture f) {
        var client = JedisPooledOperations.of(f.redis);
        var config = RedisGuardrailConfig.builder().enabled(false).build();
        var guard = new RedisProducerGuard(client, config, WriteBehindConfig.defaults(), f.keys, "unused", 64);
        var policy = CachePolicy.builder().hotEntityLimit(CachePolicy.UNBOUNDED).lruEvictionEnabled(false).build();
        var shape = ReadShapeGuardrailConfig.defaults();
        var pages = new RedisPageCacheManager<>(client, f.metadata, f.codec, policy, f.keys,
                new RedisHotSetManager(client, f.keys), f.manager, guard, shape, null);
        WriteBehindQueue queue = new WriteBehindQueue() { public <T, ID> void enqueue(WriteOperation<T, ID> operation) {} };
        return new RedisEntityRepository<>(client, f.metadata, f.codec, queue, f.keys, FetchPlan.empty(), policy,
                null, guard, config, "unused", "unused", 1, null, (r, c) -> {}, RelationConfig.defaults(),
                QueryIndexConfig.defaults(), null, null, null, PageCacheConfig.defaults(), ReadThroughConfig.defaults(),
                shape, pages, new QueryEvaluator(), f.manager, null, client, null, null);
    }

    @Test void realEpochMillisUsesAttestedWindowAndKeepsExactRangeBounds() {
        try (var f = new TypedFixture("java.lang.Long", Long::valueOf)) {
            assertEquals(RedisCompositeMigrationFence.LONG53_VERIFIED, f.redis.get(flag(f)));
            long timeline = 1_800_000_000_000L;
            put(f, "older", timeline); put(f, "newer", timeline + 1);
            var window = f.manager.resolveCompositeQueryWindow(query(f, QueryFilter.gt("score", timeline)), row -> true).orElseThrow();
            assertEquals(List.of("newer"), window.rows().stream().map(row -> row.entity().id()).toList());
            assertEquals(1, window.rounds());
            long maximum = RedisLexicalScoreContract.MAX_LONG53;
            put(f, "edge", maximum); put(f, "negative-edge", -maximum);
            assertEquals(List.of("edge"), f.repo.query(query(f, QueryFilter.gte("score", maximum)))
                    .stream().map(TypedRow::id).toList());
            assertTrue(f.repo.query(query(f, QueryFilter.gt("score", Long.MAX_VALUE))).isEmpty());
            assertEquals(4, f.repo.query(query(f, QueryFilter.gt("score", Long.MIN_VALUE))).size());
            assertEquals(-1, f.redis.pttl(flag(f)));
        }
    }

    @Test void missingMarkerFallsBackAndSafeWritesCannotSelfAttest() {
        try (var f = new TypedFixture("java.lang.Long", Long::valueOf)) {
            f.redis.del(flag(f)); put(f, "one", 1);
            assertNull(f.redis.get(flag(f)));
            assertTrue(f.manager.resolveCompositeQueryWindow(query(f), row -> true).isEmpty());
            assertEquals(List.of("one"), f.repo.query(query(f)).stream().map(TypedRow::id).toList());
            assertTrue(f.manager.rebuildCompositeIndexesFenced(false, "attest").degradedCleared());
            assertEquals(RedisCompositeMigrationFence.LONG53_VERIFIED, f.redis.get(flag(f)));
            assertTrue(f.manager.resolveCompositeQueryWindow(query(f), row -> true).isPresent());
        }
    }

    @Test void unsafeLongBusinessWritesSucceedButPoisonUntilAFullySafeFencedRebuild() {
        try (var f = new TypedFixture("java.lang.Long", Long::valueOf)) {
            var repository = writer(f);
            long low = RedisLexicalScoreContract.MAX_LONG53 + 1, high = low + 1;
            assertNotNull(repository.saveWithReceipt(new TypedRow("z", low)));
            assertNotNull(repository.saveWithReceipt(new TypedRow("a", high)));
            assertEquals((double) low, (double) high);
            assertEquals(RedisCompositeMigrationFence.LONG53_UNSAFE, f.redis.get(flag(f)));
            assertTrue(f.manager.resolveCompositeQueryWindow(query(f), row -> true).isEmpty());
            assertEquals(List.of("z", "a"), repository.query(query(f)).stream().map(TypedRow::id).toList());
            assertEquals(List.of("a"), repository.query(query(f, QueryFilter.gt("score", low))).stream().map(TypedRow::id).toList());
            assertTrue(f.manager.rebuildCompositeIndexesFenced(false, "still-unsafe").degradedCleared());
            assertEquals(RedisCompositeMigrationFence.LONG53_UNSAFE, f.redis.get(flag(f)));
            repository.save(new TypedRow("z", 1L)); repository.save(new TypedRow("a", 2L));
            assertEquals(RedisCompositeMigrationFence.LONG53_UNSAFE, f.redis.get(flag(f)));
            assertTrue(f.manager.rebuildCompositeIndexesFenced(false, "safe-again").degradedCleared());
            assertEquals(RedisCompositeMigrationFence.LONG53_VERIFIED, f.redis.get(flag(f)));
        }
    }

    @Test void sameRangeLuaChecksDomainFlagNotAPreviousJavaDecision() {
        try (var f = new TypedFixture("java.lang.Long", Long::valueOf)) {
            put(f, "one", 1);
            var ranges = new java.util.concurrent.atomic.AtomicInteger();
            var counted = new RedisClientOperations() {
                public redis.clients.jedis.UnifiedJedis commands() { return f.redis; }
                public Object eval(String script, List<String> keys, List<String> args) {
                    if (script.contains("local probes=0")) {
                        ranges.incrementAndGet(); assertEquals(7, keys.size()); assertEquals(flag(f), keys.get(6));
                        f.redis.set(flag(f), RedisCompositeMigrationFence.LONG53_UNSAFE);
                    }
                    return RedisClientOperations.super.eval(script, keys, args);
                }
            };
            assertTrue(manager(f, counted).resolveCompositeQueryWindow(query(f), row -> true).isEmpty());
            assertEquals(1, ranges.get());
        }
    }

    @Test void activeWriterBlocksPromotionAndFastReadsUntilAtomicPoisonAndRelease() throws Exception {
        try (var f = new TypedFixture("java.lang.Long", Long::valueOf);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            var independent = manager(f, JedisPooledOperations.of(f.redis));
            var writer = executor.submit(() -> f.manager.withCompositeMutation(() -> {
                var row = new TypedRow("unsafe", Long.MAX_VALUE);
                f.manager.validateCompositeValues(f.codec.toColumns(row)); started.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Writer timed out"); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException(stopped); }
                f.redis.set(f.keys.entityKey("rows", row.id()), f.codec.toRedisValue(row));
                f.manager.reindex(row); return null;
            }));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertTrue(independent.resolveCompositeQueryWindow(query(f), row -> true).isEmpty());
                assertThrows(RuntimeException.class, () -> independent.rebuildCompositeIndexesFenced(false, "must-block"));
            } finally { release.countDown(); }
            writer.get(5, TimeUnit.SECONDS);
            assertEquals(RedisCompositeMigrationFence.LONG53_UNSAFE, f.redis.get(flag(f)));
            assertTrue(independent.resolveCompositeQueryWindow(query(f), row -> true).isEmpty());
        }
    }

    @Test void unknownPoisonReleaseOutcomeLeavesEitherUnsafeFlagOrPersistentWriterBlocker() {
        for (boolean afterRedisExecution : List.of(false, true)) {
            try (var f = new TypedFixture("java.lang.Long", Long::valueOf)) {
                var faulty = new RedisClientOperations() {
                    public redis.clients.jedis.UnifiedJedis commands() { return f.redis; }
                    public Object eval(String script, List<String> keys, List<String> args) {
                        if (!script.contains("Composite writer ownership lost"))
                            return RedisClientOperations.super.eval(script, keys, args);
                        if (afterRedisExecution) RedisClientOperations.super.eval(script, keys, args);
                        throw new IllegalStateException("Unknown release outcome");
                    }
                };
                var manager = manager(f, faulty);
                assertThrows(IllegalStateException.class, () -> put(f, manager, "unsafe", Long.MIN_VALUE));
                assertNotNull(f.redis.get(f.keys.entityKey("rows", "unsafe")));
                assertTrue(f.manager.resolveCompositeQueryWindow(query(f), row -> true).isEmpty());
                if (afterRedisExecution) assertEquals(RedisCompositeMigrationFence.LONG53_UNSAFE, f.redis.get(flag(f)));
                else assertThrows(RuntimeException.class, () -> f.manager.rebuildCompositeIndexesFenced(false, "orphan"));
            }
        }
    }

    @Test @EnabledIfSystemProperty(named="cachedb.redis.cluster", matches="true")
    void domainProofAndWriterPoisonShareTheCompositeClusterSlot() {
        var config = redis.clients.jedis.DefaultJedisClientConfig.builder().connectionTimeoutMillis(3000).socketTimeoutMillis(5000)
                .hostAndPortMapper(address -> System.getProperty("cachedb.redis.cluster.host") != null ? address
                        : new redis.clients.jedis.HostAndPort("127.0.0.1", address.getPort() < 10000
                                ? address.getPort() + 10000 : address.getPort())).build();
        try (var client = new JedisClusterOperations(new redis.clients.jedis.JedisCluster(
                Set.of(new redis.clients.jedis.HostAndPort(System.getProperty("cachedb.redis.cluster.host", "127.0.0.1"),
                        Integer.getInteger("cachedb.redis.cluster.port", 17001))), config))) {
            String prefix = "long53-cluster-" + UUID.randomUUID();
            var keys = new ClusterHashTagKeyStrategy(prefix);
            var metadata = new com.reactor.cachedb.core.model.EntityMetadata<TypedRow, String>() {
                public String entityName() { return "LongCluster"; } public String tableName() { return "rows"; }
                public String redisNamespace() { return "rows"; } public String idColumn() { return "id"; }
                public Class<TypedRow> entityType() { return TypedRow.class; }
                public java.util.function.Function<TypedRow, String> idAccessor() { return TypedRow::id; }
                public List<String> columns() { return List.of("id", "status", "score"); }
                public Map<String, String> columnTypes() { return Map.of("id", "java.lang.String", "status", "java.lang.String", "score", "java.lang.Long"); }
                public List<com.reactor.cachedb.core.model.RelationDefinition> relations() { return List.of(); }
                public Map<String, List<String>> compositeExactIndexes() { return Map.of("status_score", List.of("status")); }
                public Map<String, String> compositeSortColumns() { return Map.of("status_score", "score"); }
            };
            var codec = new com.reactor.cachedb.core.model.EntityCodec<TypedRow>() {
                public String toRedisValue(TypedRow row) { return row.id() + "|" + row.score(); }
                public TypedRow fromRedisValue(String value) {
                    int split = value.indexOf('|'); return new TypedRow(value.substring(0, split), Long.valueOf(value.substring(split + 1)));
                }
                public Map<String, Object> toColumns(TypedRow row) { return Map.of("id", row.id(), "status", "OPEN", "score", row.score()); }
            };
            var manager = new RedisQueryIndexManager<>(client, metadata, codec, keys, null, QueryIndexConfig.defaults(),
                    RelationConfig.defaults(), new QueryEvaluator(), null);
            String flag = keys.compositeIndexSignatureKey("rows") + ":long53:status_score";
            String exact = RedisCompositeIndexes.key(keys, "rows", "status_score", List.of("status"), Map.of("status", "OPEN"));
            assertEquals(redis.clients.jedis.util.JedisClusterCRC16.getSlot(exact),
                    redis.clients.jedis.util.JedisClusterCRC16.getSlot(flag));
            try {
                assertTrue(manager.rebuildCompositeIndexesFenced(false, "empty").degradedCleared());
                for (long value : new long[]{1, Long.MAX_VALUE}) {
                    var row = new TypedRow(Long.toString(value), value);
                    manager.withCompositeMutation(() -> {
                        manager.validateCompositeValues(codec.toColumns(row));
                        client.commands().set(keys.entityKey("rows", row.id()), codec.toRedisValue(row));
                        manager.reindex(row); return null;
                    });
                    var query = QuerySpec.allOf(QueryFilter.eq("status", "OPEN")).orderBy(QuerySort.asc("score")).limitTo(1);
                    assertEquals(value == 1, manager.resolveCompositeQueryWindow(query, rowValue -> true).isPresent());
                }
                assertEquals(RedisCompositeMigrationFence.LONG53_UNSAFE, client.commands().get(flag));
            } finally {
                String cursor = "0"; int rounds = 0;
                do {
                    var scan = client.scan(cursor, new redis.clients.jedis.params.ScanParams()
                            .match(prefix + ":*").count(256));
                    for (String key : scan.getResult()) client.commands().unlink(key);
                    cursor = scan.getCursor();
                    if (++rounds > 10000) throw new IllegalStateException("Cluster cleanup bound exceeded");
                } while (!cursor.equals("0"));
            }
        }
    }

    @Test void typedCursorDoesNotSilentlyBecomeADoubleLexicalLongCursor() {
        try (var f = new TypedFixture("java.lang.Long", Long::valueOf)) {
            put(f, "safe", 1);
            assertThrows(IllegalArgumentException.class, () -> f.manager.requireRedisLexicalContract(query(f), "status_score"));
            assertTrue(f.manager.resolveCompositeWindow(query(f),
                    new RedisQueryIndexManager.CompositePosition(1, "safe"), row -> true).isEmpty());
            assertEquals(4, RedisQueryIndexManager.COMPOSITE_MAX_ROUNDS);
            assertEquals(10000, RedisQueryIndexManager.COMPOSITE_MAX_EXAMINED_IDS);
            var tooMany = java.util.stream.IntStream.range(0, 33).mapToObj(i -> flag(f) + i).toList();
            assertThrows(IllegalArgumentException.class, () -> new RedisCompositeMigrationFence(
                    JedisPooledOperations.of(f.redis), f.keys, "rows", tooMany));
        }
    }
}

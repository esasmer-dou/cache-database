package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisFacetNativeBatchRedisTest {
    @Test void twoFieldFacetWindowsStay256AndLargeBatchesUseAtomicLua() {
        for (boolean tenantScoped : List.of(false, true)) {
            try (var redis = new CountingRedis(); var fixture = new RedisT4AggregationRedisTest.Fixture(redis, tenantScoped);
                    var tenant = tenantScoped ? CacheTenantContext.withTenant("a") : CacheTenantContext.administrative()) {
                var rows = new ArrayList<RedisT4AggregationRedisTest.Row>();
                for (long id = 1; id <= 513; id++) rows.add(row(id, "a", "g", "ok"));
                fixture.seed(rows); redis.counter.evaluations = 0;
                var operations = new NativeOperations(redis);
                var repository = repository(operations, fixture, CachePolicy.defaults());
                var reference = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, false), CachePolicy.defaults());
                for (AggregateFunction function : AggregateFunction.values()) {
                    var spec = new AggregateSpec(function, function == AggregateFunction.COUNT ? null : "amount", null);
                    var expected = reference.aggregateBy("category", spec);
                    redis.counter.evaluations = 0; operations.batches.clear();
                    var actual = repository.aggregateBy("category", spec);
                    assertEquals(function == AggregateFunction.COUNT ? List.of(512, 1) : List.of(256, 256, 1),
                            operations.batches);
                    assertEquals(function == AggregateFunction.COUNT ? 1 : 2, redis.counter.evaluations,
                            "Each large window must use exactly one atomic Lua call; the one-row tail stays native");
                    RedisT4AggregationRedisTest.assertFacets(expected, actual);
                    assertEquals(513, actual.get("g").count());
                    if (function == AggregateFunction.SUM)
                        assertEquals(new BigDecimal("64.125"), actual.get("g").sum());
                }
                var spec = new AggregateSpec(AggregateFunction.COUNT, "label", null);
                var expected = reference.aggregateBy("category", spec);
                redis.counter.evaluations = 0; operations.batches.clear();
                RedisT4AggregationRedisTest.assertFacets(expected, repository.aggregateBy("category", spec));
                assertEquals(List.of(256, 256, 1), operations.batches);
                assertEquals(2, redis.counter.evaluations);
            }
        }
    }

    @Test void boundariesSentinelsAndStaleTenantCandidatesMatchOwnerAwareLua() {
        try (var redis = new CountingRedis(); var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                var tenant = CacheTenantContext.withTenant("a")) {
            var rows = new ArrayList<RedisT4AggregationRedisTest.Row>();
            for (long id = 1; id <= 513; id++) {
                rows.add(row(id, id == 259 ? "b" : "a",
                        id == 256 ? "__NULL__" : id == 257 ? null : id == 258 ? "" : "g",
                        id == 256 ? "__NULL__" : id == 257 ? null : "ok"));
            }
            fixture.seed(rows);
            redis.sadd(fixture.keys.indexExactKey("t4_rows", "tenant", RedisIndexKeySpace.encodeKeyPart("a")), "259");
            redis.del(fixture.owner(260)); redis.del(fixture.entity(261)); redis.set(fixture.tombstone(262), "deleted");
            var operations = new NativeOperations(redis);
            var repository = repository(operations, fixture, CachePolicy.defaults());
            var reference = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, true), CachePolicy.defaults());
            for (var spec : List.of(AggregateSpec.sum("amount", null),
                    new AggregateSpec(AggregateFunction.COUNT, "label", null))) {
                operations.batches.clear();
                var actual = repository.aggregateBy("category", spec);
                assertEquals(List.of(256, 256, 1), operations.batches);
                RedisT4AggregationRedisTest.assertFacets(reference.aggregateBy("category", spec), actual);
                assertEquals(Set.of("g", "", "__NULL__"), actual.keySet());
                assertEquals(508L, actual.values().stream().mapToLong(AggregateResult::count).sum());
                assertEquals(1, actual.get("__NULL__").count());
            }
            assertTrue(redis.counter.evaluations > 0, "Mixed liveness and sentinel payload checks retain Lua");
        }
    }

    @Test void facetGroupCapRemainsOperationWideAcross256RowBatches() {
        try (var redis = new CountingRedis(); var fixture = new RedisT4AggregationRedisTest.Fixture(redis, false);
                var tenant = CacheTenantContext.administrative()) {
            var rows = new ArrayList<RedisT4AggregationRedisTest.Row>();
            for (long id = 1; id <= 257; id++) rows.add(row(id, "a", "g" + id, "ok"));
            fixture.seed(rows); redis.counter.evaluations = 0;
            var operations = new NativeOperations(redis);
            var repository = repository(operations, fixture, CachePolicy.defaults());
            assertTrue(assertThrows(IllegalStateException.class, () -> repository.aggregateBy("category",
                    AggregateSpec.sum("amount", null))).getMessage().contains("Facet group limit"));
            assertEquals(List.of(256, 1), operations.batches);
            assertEquals(1, redis.counter.evaluations);
        }
    }

    @Test void facetByteCapDoesNotResetAtEachAtomicBatch() {
        try (var redis = new CountingRedis(); var fixture = new RedisT4AggregationRedisTest.Fixture(redis, false);
                var tenant = CacheTenantContext.administrative()) {
            String label = "x".repeat(20_000);
            var rows = new ArrayList<RedisT4AggregationRedisTest.Row>();
            for (long id = 1; id <= 512; id++) rows.add(row(id, "a", "g", "ok"));
            fixture.seed(rows);
            for (long id = 1; id <= 512; id++)
                redis.hset(fixture.keys.indexMetaKey("t4_rows", id), "label", label);
            redis.counter.evaluations = 0;
            var operations = new NativeOperations(redis);
            var repository = repository(operations, fixture, CachePolicy.defaults());
            var spec = new AggregateSpec(AggregateFunction.COUNT, "label", null);
            assertTrue(assertThrows(IllegalStateException.class, () -> repository.aggregateBy("category", spec))
                    .getMessage().contains("Facet byte budget"));
            assertEquals(List.of(256, 256), operations.batches);
            assertEquals(2, redis.counter.evaluations);
        }
    }

    @Test void candidateCapRejectsBeforeAnyNativeSubBatch() {
        try (var redis = new CountingRedis(); var fixture = new RedisT4AggregationRedisTest.Fixture(redis, false);
                var tenant = CacheTenantContext.administrative()) {
            String all = fixture.keys.indexAllKey("t4_rows"); fixture.cleanup.add(all);
            for (int start = 0; start < 100_001; start += 512) {
                var ids = new ArrayList<String>();
                for (int id = start; id < Math.min(100_001, start + 512); id++) ids.add(Integer.toString(id));
                redis.sadd(all, ids.toArray(String[]::new));
            }
            var operations = new NativeOperations(redis);
            var repository = repository(operations, fixture, CachePolicy.defaults());
            assertTrue(assertThrows(IllegalStateException.class, () -> repository.aggregateBy("category",
                    AggregateSpec.sum("amount", null))).getMessage().contains("candidate limit"));
            assertTrue(operations.batches.isEmpty());
        }
    }

    @Test void customHotPolicyRetainsPayloadPathInsteadOfForcing256RowIndexReads() {
        try (var redis = new CountingRedis(); var fixture = new RedisT4AggregationRedisTest.Fixture(redis, false);
                var tenant = CacheTenantContext.administrative()) {
            fixture.seed(List.of(row(1, "a", "g", "ok")));
            var operations = new NativeOperations(redis);
            var policy = CachePolicy.builder().customHotPredicate(columns -> true).build();
            var repository = repository(operations, fixture, policy);
            assertEquals(1, repository.aggregateBy("category", AggregateSpec.sum("amount", null)).get("g").count());
            assertTrue(operations.batches.isEmpty());
        }
    }

    private static RedisT4AggregationRedisTest.Row row(long id, String tenant, String group, String label) {
        return new RedisT4AggregationRedisTest.Row(id, tenant, group, new BigDecimal("0.125"), label);
    }

    private static RedisEntityRepository<RedisT4AggregationRedisTest.Row, Long> repository(
            RedisClientOperations operations, RedisT4AggregationRedisTest.Fixture fixture, CachePolicy policy) {
        var config = QueryIndexConfig.builder().maxMaterializedCandidateIds(100_001).build();
        var indexes = new RedisQueryIndexManager<>(operations, fixture.metadata, RedisT4AggregationRedisTest.CODEC,
                fixture.keys, null, config, RelationConfig.defaults(), new QueryEvaluator(), null);
        var pages = new RedisPageCacheManager<>(operations, fixture.metadata, RedisT4AggregationRedisTest.CODEC,
                policy, fixture.keys, null, indexes, null, ReadShapeGuardrailConfig.defaults());
        return new RedisEntityRepository<>(operations, fixture.metadata, RedisT4AggregationRedisTest.CODEC,
                null, fixture.keys, FetchPlan.empty(), policy, null, null, RedisGuardrailConfig.defaults(),
                "unused:stream", "unused:compaction", 1, null, null, RelationConfig.defaults(), config,
                null, PageCacheConfig.defaults(), ReadShapeGuardrailConfig.defaults(), pages, new QueryEvaluator(),
                indexes, null, operations, null, null);
    }

    private static final class NativeOperations implements RedisClientOperations {
        final JedisPooledOperations nativeAdapter;
        final List<Integer> batches = new ArrayList<>();
        NativeOperations(JedisPooled redis) { nativeAdapter = new JedisPooledOperations(redis); }
        public UnifiedJedis commands() { return nativeAdapter.commands(); }
        @Override public Optional<FacetIndexBatch> facetIndexBatch(List<String> entities, List<String> tombstones,
                List<String> metadata, List<String> owners, String tenant, String group, String value) {
            batches.add(entities.size());
            return nativeAdapter.facetIndexBatch(entities, tombstones, metadata, owners, tenant, group, value);
        }
    }

    private static final class Counter { int evaluations; }

    private static final class CountingRedis extends JedisPooled {
        final Counter counter;
        CountingRedis() { this(new Counter()); }
        private CountingRedis(Counter counter) {
            super(new BasePooledObjectFactory<Connection>() {
                public Connection create() {
                    URI uri = URI.create(RedisTestEndpoint.uri());
                    return new Connection(new HostAndPort(uri.getHost(), uri.getPort()),
                            DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000)
                                    .socketTimeoutMillis(2000).build()) {
                        @Override public void sendCommand(CommandArguments args) {
                            if (Arrays.equals(args.getCommand().getRaw(), Protocol.Command.EVAL.getRaw()))
                                counter.evaluations++;
                            super.sendCommand(args);
                        }
                    };
                }
                public PooledObject<Connection> wrap(Connection connection) { return new DefaultPooledObject<>(connection); }
                @Override public void destroyObject(PooledObject<Connection> value) {
                    value.getObject().setHandlingPool(null); value.getObject().close();
                }
                @Override public boolean validateObject(PooledObject<Connection> value) {
                    return value.getObject().isConnected() && !value.getObject().isBroken();
                }
            }, config());
            this.counter = counter;
        }
        private static GenericObjectPoolConfig<Connection> config() {
            var config = new GenericObjectPoolConfig<Connection>();
            config.setMaxTotal(2); config.setMaxIdle(2); config.setJmxEnabled(false);
            config.setMaxWait(Duration.ofSeconds(2)); return config;
        }
    }
}

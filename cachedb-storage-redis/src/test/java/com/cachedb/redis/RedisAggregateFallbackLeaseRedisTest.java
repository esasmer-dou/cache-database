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
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisDataException;

@Timeout(30)
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisAggregateFallbackLeaseRedisTest {
    @Test void ownedCountAndSumFallbackReleasePipelineBeforeBoundedMgetWithOneConnection() {
        try (var redis = client(); var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                var scope = CacheTenantContext.withTenant("a")) {
            seed(fixture);
            var optimized = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, true), CachePolicy.defaults());
            for (var spec : List.of(AggregateSpec.count(null), AggregateSpec.sum("amount", null))) {
                var expected = optimized.aggregate(spec);
                assertEquals(597, expected.count());
                if (spec.function() == AggregateFunction.SUM)
                    assertEquals(0, new BigDecimal("74.500").compareTo(expected.sum()));
                assertEquals(0, redis.getPool().getNumActive());
                var fallback = new FallbackClient(redis);
                var actual = repository(fixture, fallback).aggregate(spec);
                assertEquals(expected, actual);
                assertEquals(expected.decimalAverage(), actual.decimalAverage());
                assertEquals(spec.function() == AggregateFunction.COUNT ? 1 : 0, fallback.phaseDeclines);
                assertEquals(2, fallback.windowDeclines);
                assertEquals(2, fallback.payloadReads);
                assertEquals(2, fallback.payloadEvals);
                assertEquals(List.of(512, 88), fallback.payloadWindows);
                assertEquals(1, redis.getPool().getMaxTotal());
                assertEquals(0, redis.getPool().getNumActive());
                assertEquals("PONG", redis.ping());
            }
        }
    }

    @Test void realBoundedMgetWrongtypePropagatesAndReturnsTheOnlyLease() {
        try (var redis = client(); var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                var scope = CacheTenantContext.withTenant("a")) {
            seed(fixture);
            var fallback = new FallbackClient(redis);
            fallback.poisonNextPayloadRead = true;
            var failure = assertThrows(JedisDataException.class,
                    () -> repository(fixture, fallback).aggregate(AggregateSpec.count(null)));
            assertTrue(failure.getMessage().contains("WRONGTYPE"));
            assertEquals(1, fallback.phaseDeclines);
            assertEquals(1, fallback.windowDeclines);
            assertEquals(1, fallback.payloadReads);
            assertEquals(1, fallback.payloadEvals);
            assertEquals(0, redis.getPool().getNumActive());
            assertEquals("PONG", redis.ping());
            assertEquals(0, redis.getPool().getNumActive());
        }
    }

    private static JedisPooled client() {
        var endpoint = URI.create(RedisTestEndpoint.uri());
        var pool = new GenericObjectPoolConfig<Connection>();
        pool.setMaxTotal(1);
        pool.setMaxIdle(1);
        pool.setMaxWait(Duration.ofSeconds(2));
        pool.setJmxEnabled(false);
        return new JedisPooled(new HostAndPort(endpoint.getHost(), endpoint.getPort()),
                DefaultJedisClientConfig.builder().connectionTimeoutMillis(2000).socketTimeoutMillis(2000).build(), pool);
    }

    private static void seed(RedisT4AggregationRedisTest.Fixture fixture) {
        fixture.seed(IntStream.rangeClosed(1, 600).mapToObj(id -> new RedisT4AggregationRedisTest.Row(
                (long) id, id == 3 ? "b" : "a", "g", id == 4 ? null : new BigDecimal("0.125"), "ok")).toList());
        fixture.redis.set(fixture.tombstone(1), "deleted");
        fixture.redis.del(fixture.entity(2));
        // A foreign row injected into tenant A's index must still be rejected on the payload fallback.
        fixture.redis.sadd(fixture.keys.indexExactKey("t4_rows", "tenant", RedisIndexKeySpace.encodeKeyPart("a")), "3");
    }

    private static RedisEntityRepository<RedisT4AggregationRedisTest.Row, Long> repository(
            RedisT4AggregationRedisTest.Fixture fixture, RedisClientOperations client) {
        var policy = CachePolicy.defaults();
        var manager = new RedisQueryIndexManager<>(client, fixture.metadata, RedisT4AggregationRedisTest.CODEC,
                fixture.keys, null, QueryIndexConfig.builder().maxMaterializedCandidateIds(100_001).build(),
                RelationConfig.defaults(), new QueryEvaluator(), null);
        var pages = new RedisPageCacheManager<>(client, fixture.metadata, RedisT4AggregationRedisTest.CODEC,
                policy, fixture.keys, null, manager, null, ReadShapeGuardrailConfig.defaults());
        return new RedisEntityRepository<>(client, fixture.metadata, RedisT4AggregationRedisTest.CODEC, null,
                fixture.keys, FetchPlan.empty(), policy, null, null, RedisGuardrailConfig.defaults(),
                "unused:stream", "unused:compaction", 1, null, null, RelationConfig.defaults(),
                QueryIndexConfig.defaults(), null, PageCacheConfig.defaults(), ReadShapeGuardrailConfig.defaults(),
                pages, new QueryEvaluator(), manager, null, client, null, null);
    }

    private static final class FallbackClient implements RedisClientOperations {
        final JedisPooled redis;
        final java.util.ArrayList<Integer> payloadWindows = new java.util.ArrayList<>();
        int phaseDeclines, windowDeclines, payloadReads, payloadEvals;
        boolean poisonNextPayloadRead, readingPayload;
        FallbackClient(JedisPooled redis) { this.redis = redis; }
        public UnifiedJedis commands() { return redis; }
        @Override public OptionalLong aggregateIndexCounts(int candidates, IntFunction<IndexReadKeys> windows,
                String tenant) {
            assertEquals(600, candidates);
            assertEquals("a", tenant);
            phaseDeclines++;
            return OptionalLong.empty();
        }
        @Override public Optional<IndexAggregateBatch> aggregateIndexBatch(List<String> entities, List<String> tombstones,
                List<String> metadata, List<String> owners, String tenant, String field, boolean countOnly) {
            assertNotNull(owners);
            assertEquals("a", tenant);
            assertTrue(entities.size() <= 512);
            windowDeclines++;
            return Optional.empty();
        }
        @Override public List<String> boundedMget(List<String> keys, long maxBytes) {
            assertEquals(0, redis.getPool().getNumActive(), "Aggregate pipeline must close before guarded payload borrow");
            payloadReads++;
            payloadWindows.add(keys.size());
            if (poisonNextPayloadRead) {
                poisonNextPayloadRead = false;
                redis.del(keys.getFirst());
                redis.rpush(keys.getFirst(), "wrong-type");
            }
            readingPayload = true;
            try { return RedisClientOperations.super.boundedMget(keys, maxBytes); }
            finally { readingPayload = false; }
        }
        @Override public Object eval(String script, List<String> keys, List<String> arguments) {
            if (readingPayload) payloadEvals++;
            return RedisClientOperations.super.eval(script, keys, arguments);
        }
    }
}

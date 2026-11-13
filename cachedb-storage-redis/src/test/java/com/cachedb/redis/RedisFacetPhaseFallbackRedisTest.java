package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisDataException;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisFacetPhaseFallbackRedisTest {
    @Test void deniedAndSoftBudgetSkipRowsPhaseButKeepBoundedLiveTenantFallback() {
        for (boolean owned : List.of(false, true)) {
            try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                    var fixture = new RedisT4AggregationRedisTest.Fixture(redis, owned);
                    var scope = owned ? CacheTenantContext.withTenant("a") : CacheTenantContext.administrative()) {
                seed(fixture);
                for (var reason : List.of(RedisClientOperations.FacetPhaseDecline.EVAL_COMMAND_DENIED,
                        RedisClientOperations.FacetPhaseDecline.SOFT_BYTE_BUDGET)) {
                    var client = new TrackingClient(redis) {
                        @Override public FacetPhaseResult<FacetIndexCounts> facetIndexCountsPhase(int candidates,
                                IntFunction<IndexReadKeys> windows, String tenant, String group) {
                            compactCalls++;
                            return FacetPhaseResult.declined(reason);
                        }
                    };
                    var result = repository(fixture, client).aggregateBy("category", AggregateSpec.count(null));
                    assertEquals(owned ? 1021 : 1022, result.values().stream().mapToLong(value -> value.count()).sum());
                    assertEquals(1, client.compactCalls);
                    assertEquals(0, client.rowsCalls);
                    assertEquals(2, client.windowCalls);
                    assertEquals(512, client.maxWindow);
                }
            }
        }
    }

    @Test void legacyUnsupportedStillUsesRowsAndLegacySuccessStillAvoidsRows() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, false);
                var scope = CacheTenantContext.administrative()) {
            seed(fixture);
            for (boolean compact : List.of(false, true)) {
                var client = new TrackingClient(redis) {
                    @Override public Optional<List<FacetIndexCounts>> facetIndexCounts(int candidates,
                            IntFunction<IndexReadKeys> windows, String tenant, String group) {
                        compactCalls++;
                        return compact ? delegate.facetIndexCounts(candidates, windows, tenant, group) : Optional.empty();
                    }
                };
                var result = repository(fixture, client).aggregateBy("category", AggregateSpec.count(null));
                assertEquals(1022, result.values().stream().mapToLong(value -> value.count()).sum());
                assertEquals(1, client.compactCalls);
                assertEquals(compact ? 0 : 1, client.rowsCalls);
                assertEquals(0, client.windowCalls);
            }
        }
    }

    @Test void compactDataErrorPropagatesWithoutRowsOrWindowFallback() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, false);
                var scope = CacheTenantContext.administrative()) {
            seed(fixture);
            var failure = new JedisDataException("WRONGTYPE");
            var client = new TrackingClient(redis) {
                @Override public FacetPhaseResult<FacetIndexCounts> facetIndexCountsPhase(int candidates,
                        IntFunction<IndexReadKeys> windows, String tenant, String group) {
                    compactCalls++;
                    throw failure;
                }
            };
            assertSame(failure, assertThrows(JedisDataException.class,
                    () -> repository(fixture, client).aggregateBy("category", AggregateSpec.count(null))));
            assertEquals(1, client.compactCalls);
            assertEquals(0, client.rowsCalls);
            assertEquals(0, client.windowCalls);
        }
    }

    private static void seed(RedisT4AggregationRedisTest.Fixture fixture) {
        fixture.seed(IntStream.rangeClosed(1, 1024).mapToObj(id -> new RedisT4AggregationRedisTest.Row(
                (long) id, "a", id % 2 == 0 ? "x" : "y", null, "ok")).toList());
        fixture.redis.set(fixture.tombstone(1), "1");
        fixture.redis.del(fixture.entity(2));
        fixture.redis.set(fixture.owner(3), "b");
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

    private static class TrackingClient implements RedisClientOperations {
        final JedisPooled redis;
        final JedisPooledOperations delegate;
        int compactCalls, rowsCalls, windowCalls, maxWindow;
        TrackingClient(JedisPooled redis) { this.redis = redis; delegate = new JedisPooledOperations(redis); }
        public UnifiedJedis commands() { return redis; }
        public boolean clustered() { return false; }
        @Override public Optional<List<FacetIndexBatch>> facetIndexBatches(int candidates,
                IntFunction<IndexReadKeys> windows, String tenant, String group, String field) {
            rowsCalls++;
            return delegate.facetIndexBatches(candidates, windows, tenant, group, field);
        }
        @Override public Optional<FacetIndexBatch> facetIndexBatch(List<String> entities, List<String> tombstones,
                List<String> metadata, List<String> owners, String tenant, String group, String field) {
            windowCalls++;
            maxWindow = Math.max(maxWindow, entities.size());
            assertTrue(entities.size() <= 512);
            return delegate.facetIndexBatch(entities, tombstones, metadata, owners, tenant, group, field);
        }
    }
}

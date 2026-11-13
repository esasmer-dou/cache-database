package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.query.AggregateSpec;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisDataException;

@Timeout(120)
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisCompactFacetCountsRedisTest {
    @Test void tenThousandRowsUseWeightedGroupsWithGlobalAndAuthoritativeTenantParity() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri())) {
            for (boolean scoped : List.of(false, true)) {
                try (var fixture = new RedisT4AggregationRedisTest.Fixture(redis, scoped);
                        var scope = scoped ? CacheTenantContext.withTenant("a") : CacheTenantContext.administrative()) {
                    fixture.seed(rows(10_000));
                    redis.set(fixture.tombstone(1), "1"); redis.del(fixture.entity(2));
                    redis.set(fixture.owner(3), "b");
                    var client = new Client(redis);
                    var repository = RedisStandaloneCountFacetPipelineRedisTest.repository(client, fixture);
                    var actual = repository.aggregateBy("category", AggregateSpec.count(null));
                    var reference = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, true), CachePolicy.defaults());
                    RedisT4AggregationRedisTest.assertFacets(reference.aggregateBy("category", AggregateSpec.count(null)), actual);
                    assertEquals(scoped ? 9_997 : 9_998, actual.values().stream().mapToLong(value -> value.count()).sum());
                    assertEquals(20, client.batches.orElseThrow().size());
                    assertTrue(client.batches.get().stream().mapToInt(batch -> batch.counts().size()).sum() <= 400);
                    assertEquals(0, client.legacyFacets, "Healthy COUNT(*) must not materialize row pairs");
                    assertEquals(0, redis.getPool().getNumActive());
                }
            }
        }
    }

    @Test void sentinelPayloadsStillRecheckOwnershipLivenessAndLiteralNullGroups() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                var scope = CacheTenantContext.withTenant("a")) {
            fixture.seed(LongStream.rangeClosed(1, 1024).mapToObj(id -> new RedisT4AggregationRedisTest.Row(
                    id, "a", switch ((int) (id % 4)) { case 0 -> null; case 1 -> "__NULL__"; case 2 -> ""; default -> "\u00c7\u0131\u011f"; },
                    BigDecimal.ONE, "x")).toList());
            var client = new Client(redis);
            var repository = RedisStandaloneCountFacetPipelineRedisTest.repository(client, fixture);
            client.beforePayload = () -> {
                redis.set(fixture.owner(1), "b"); redis.set(fixture.tombstone(5), "1"); redis.del(fixture.entity(9));
            };
            var actual = repository.aggregateBy("category", AggregateSpec.count(null));
            var reference = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, true), CachePolicy.defaults());
            RedisT4AggregationRedisTest.assertFacets(reference.aggregateBy("category", AggregateSpec.count(null)), actual);
            assertEquals(253, actual.get("__NULL__").count());
            assertEquals(256, actual.get("").count()); assertEquals(256, actual.get("\u00c7\u0131\u011f").count());
            assertEquals(Set.of("__NULL__", "", "\u00c7\u0131\u011f"), actual.keySet());
            assertEquals(2, client.batches.orElseThrow().size());
            assertTrue(client.payloads > 0); assertEquals(0, redis.getPool().getNumActive());
        }
    }

    @Test void wrongTypesMissingMetadataAndOversizedOwnersNeverBecomePartialHistograms() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            fixture.seed(rows(1536));
            var operations = new JedisPooledOperations(redis);
            for (long id : List.of(1L, 769L, 1536L)) {
                String metadata = fixture.keys.indexMetaKey("t4_rows", id);
                redis.hdel(metadata, "category");
                assertTrue(assertThrows(JedisDataException.class, () -> operations.facetIndexCounts(
                        1536, windows(fixture, 1536), "a", "category")).getMessage().contains("Incomplete facet index metadata"));
                redis.hset(metadata, "category", "g" + id % 20);
                redis.del(fixture.owner(id)); redis.hset(fixture.owner(id), "wrong", "type");
                assertTrue(assertThrows(JedisDataException.class, () -> operations.facetIndexCounts(
                        1536, windows(fixture, 1536), "a", "category")).getMessage().contains("WRONGTYPE"));
                redis.del(fixture.owner(id)); redis.set(fixture.owner(id), "a".repeat(1_000_000));
                var result = operations.facetIndexCounts(1536, windows(fixture, 1536), "a", "category").orElseThrow();
                assertEquals(1535, result.stream().flatMap(batch -> batch.counts().values().stream()).mapToLong(Long::longValue).sum());
                redis.set(fixture.owner(id), "a");
            }
            assertEquals(0, redis.getPool().getNumActive());
        }
    }

    @Test void allLiveWindowsBatchOnlyLengthQualifiedUnicodeOwners() {
        String owner = "\u015firket";
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true);
                var scope = CacheTenantContext.withTenant(owner)) {
            fixture.seed(LongStream.rangeClosed(1, 1536).mapToObj(id -> new RedisT4AggregationRedisTest.Row(
                    id, owner, "g" + id % 20, BigDecimal.ONE, "x")).toList());
            for (long first : List.of(1L, 513L, 1025L)) {
                redis.set(fixture.owner(first), "foreign");
                redis.del(fixture.owner(first + 1));
                redis.set(fixture.owner(first + 2), "");
                redis.set(fixture.owner(first + 3), owner + "x".repeat(1_000_000));
                for (long id = first; id < first + 4; id++) {
                    String metadata = fixture.keys.indexMetaKey("t4_rows", id);
                    redis.del(metadata); redis.set(metadata, "not a hash");
                }
            }
            var client = new Client(redis);
            var repository = RedisStandaloneCountFacetPipelineRedisTest.repository(client, fixture);
            var count = AggregateSpec.count(null);
            var actual = repository.aggregateBy("category", count);
            var reference = fixture.repository(new RedisT4AggregationRedisTest.Client(redis, true), CachePolicy.defaults());
            RedisT4AggregationRedisTest.assertFacets(reference.aggregateBy("category", count), actual);
            assertEquals(1524, actual.values().stream().mapToLong(value -> value.count()).sum());
            assertEquals(3, client.batches.orElseThrow().size());
            assertEquals(0, client.legacyFacets);
            assertEquals(0, redis.getPool().getNumActive());
        }
    }

    @Test void phaseBudgetDeclinesOnlyAfterCheckingLaterMetadataAndKeepsNativeTails() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var fixture = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            fixture.seed(rows(10_000));
            var operations = new JedisPooledOperations(redis);
            var windows = windows(fixture, 10_000);
            for (String metadata : windows.apply(0).indexMetadata()) redis.hset(metadata, "category", "x".repeat(4096));
            assertTrue(operations.facetIndexCounts(10_000, windows, "a", "category").isEmpty());
            redis.hdel(windows.apply(9728).indexMetadata().getLast(), "category");
            assertTrue(assertThrows(JedisDataException.class, () -> operations.facetIndexCounts(
                    10_000, windows, "a", "category")).getMessage().contains("Incomplete facet index metadata"));
            assertTrue(operations.facetIndexCounts(513, ignored -> { throw new AssertionError("No tail window expected"); },
                    "a", "category").isEmpty());
            assertEquals(0, redis.getPool().getNumActive());
        }
    }

    private static List<RedisT4AggregationRedisTest.Row> rows(int size) {
        return LongStream.rangeClosed(1, size).mapToObj(id -> new RedisT4AggregationRedisTest.Row(
                id, "a", "g" + id % 20, BigDecimal.ONE, "x")).toList();
    }

    private static IntFunction<RedisClientOperations.IndexReadKeys> windows(RedisT4AggregationRedisTest.Fixture fixture, int size) {
        return start -> {
            var ids = LongStream.rangeClosed(start + 1L, Math.min(size, start + 512L)).boxed().toList();
            return new RedisClientOperations.IndexReadKeys(ids.stream().map(fixture::entity).toList(),
                    ids.stream().map(fixture::tombstone).toList(),
                    ids.stream().map(id -> fixture.keys.indexMetaKey("t4_rows", id)).toList(),
                    ids.stream().map(fixture::owner).toList());
        };
    }

    private static final class Client implements RedisClientOperations {
        final JedisPooled redis;
        final JedisPooledOperations delegate;
        Optional<List<FacetIndexCounts>> batches = Optional.empty();
        int legacyFacets, payloads;
        Runnable beforePayload;
        Client(JedisPooled redis) { this.redis = redis; delegate = new JedisPooledOperations(redis); }
        public UnifiedJedis commands() { return redis; }
        public Optional<List<FacetIndexCounts>> facetIndexCounts(int candidates, IntFunction<IndexReadKeys> windows,
                String tenant, String field) {
            return batches = delegate.facetIndexCounts(candidates, windows, tenant, field);
        }
        public Optional<FacetIndexBatch> facetIndexBatch(List<String> entities, List<String> tombstones,
                List<String> metadata, List<String> owners, String tenant, String group, String field) {
            legacyFacets++;
            return delegate.facetIndexBatch(entities, tombstones, metadata, owners, tenant, group, field);
        }
        public Optional<List<String>> liveIndexPayloadBatch(List<String> entities, List<String> tombstones,
                List<String> owners, String tenant) {
            payloads++;
            var hook = beforePayload; beforePayload = null;
            if (hook != null) hook.run();
            return delegate.liveIndexPayloadBatch(entities, tombstones, owners, tenant);
        }
    }
}

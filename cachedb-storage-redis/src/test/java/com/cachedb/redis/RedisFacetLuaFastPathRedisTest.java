package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;

@Timeout(120)
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisFacetLuaFastPathRedisTest {
    private static final long BUDGET = 1024 * 1024;

    @Test void allMatchingUnicodeOwnersUseTheOriginalSliceAndStillCompareExactValues() {
        String tenant = "\u015firket";
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var f = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            seed(f, 6, tenant);
            assertPositions(redis, f, 6, tenant, positions(6));
            redis.set(f.owner(1), "x".repeat(tenant.getBytes(StandardCharsets.UTF_8).length));
            corruptMetadata(redis, f, 1);
            assertPositions(redis, f, 6, tenant, List.of(1, 2, 3, 4, 5));
        }
    }

    @Test void firstMiddleAndLastLengthMismatchesRetainTheAlreadyCheckedPrefix() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var f = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            seed(f, 6, "a");
            for (long rejected : List.of(1L, 3L, 6L)) {
                redis.set(f.owner(rejected), "different-length");
                corruptMetadata(redis, f, rejected);
                assertPositions(redis, f, 6, "a", positions(6).stream()
                        .filter(position -> position != rejected - 1).toList());
                redis.set(f.owner(rejected), "a");
                redis.del(metadata(f, rejected));
                redis.hset(metadata(f, rejected), Map.of("category", "g" + rejected % 2, "amount", "1"));
            }
        }
    }

    @Test void missingEmptyOversizedAndForeignOwnersNeverReadTheirMetadata() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var f = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            seed(f, 8, "a");
            redis.del(f.owner(1));
            redis.set(f.owner(2), "");
            redis.set(f.owner(3), "a".repeat(1_000_000));
            redis.set(f.owner(4), "foreign");
            redis.set(f.owner(5), "b");
            for (long id = 1; id <= 5; id++) corruptMetadata(redis, f, id);
            assertPositions(redis, f, 8, "a", List.of(5, 6, 7));
        }
    }

    @Test void noLengthQualifiedOwnersReturnNoRowsWithoutReadingMetadata() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var f = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            seed(f, 6, "a");
            for (long id = 1; id <= 6; id++) {
                redis.set(f.owner(id), "");
                corruptMetadata(redis, f, id);
            }
            assertPositions(redis, f, 6, "a", List.of());
        }
    }

    @Test void laterOwnerWrongtypePrecedesMetadataReadsWithAndWithoutAnEarlierMismatch() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var f = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            seed(f, 6, "a");
            redis.hdel(metadata(f, 2), "category");
            redis.del(f.owner(6));
            redis.hset(f.owner(6), "invalid", "owner");
            for (String firstOwner : List.of("a", "")) {
                redis.set(f.owner(1), firstOwner);
                for (boolean compact : List.of(false, true)) {
                    var failure = assertThrows(JedisDataException.class,
                            () -> evaluate(redis, command(f, 6, "a", compact, BUDGET)));
                    assertTrue(failure.getMessage().startsWith("WRONGTYPE"));
                }
            }
        }
    }

    @Test void mixedLivenessKeepsPerKeyOwnerChecksAndSkipsDeadRows() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var f = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            seed(f, 6, "a");
            redis.del(f.entity(1));
            redis.set(f.tombstone(2), "deleted");
            redis.del(f.owner(1), f.owner(2));
            redis.hset(f.owner(1), "invalid", "owner");
            redis.hset(f.owner(2), "invalid", "owner");
            redis.set(f.owner(3), "b");
            redis.del(f.owner(4));
            for (long id = 1; id <= 4; id++) corruptMetadata(redis, f, id);
            assertPositions(redis, f, 6, "a", List.of(4, 5));
        }
    }

    @Test void phaseBudgetMarkersStillValidateLaterMissingAndWrongtypeMetadata() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var f = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            seed(f, 6, "a");
            for (boolean compact : List.of(false, true)) {
                var request = command(f, 6, "a", compact, 1);
                assertEquals(List.of("cachedb-facet-phase-budget-v1", 6L, 1L), evaluate(redis, request));
                redis.hdel(metadata(f, 6), "category");
                assertTrue(assertThrows(JedisDataException.class, () -> evaluate(redis, request))
                        .getMessage().contains("Incomplete facet index metadata"));
                corruptMetadata(redis, f, 6);
                assertTrue(assertThrows(JedisDataException.class, () -> evaluate(redis, request))
                        .getMessage().startsWith("WRONGTYPE"));
                redis.del(metadata(f, 6));
                redis.hset(metadata(f, 6), Map.of("category", "g0", "amount", "1"));
            }
        }
    }

    @Test void compactEmptyGroupsSentinelPositionsAndExact256GroupLimitRemainIntact() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var f = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            seed(f, 257, "a");
            redis.hset(metadata(f, 1), "category", "");
            redis.hset(metadata(f, 2), "category", "__NULL__");
            var request = command(f, 257, "a", true, BUDGET);
            var counts = RedisClientOperations.parseFacetIndexCounts(evaluate(redis, request), 257, BUDGET);
            assertEquals(1L, counts.counts().get(""));
            assertEquals(List.of(1), counts.nullCandidates());
            assertEquals(256L, counts.counts().values().stream().mapToLong(Long::longValue).sum());
            try (var pipeline = redis.pipelined()) {
                for (long id = 1; id <= 257; id++) pipeline.hset(metadata(f, id), "category", "unique-" + id);
                pipeline.sync();
            }
            assertTrue(assertThrows(JedisDataException.class, () -> evaluate(redis, request))
                    .getMessage().contains("Facet group limit exceeded: max 256"));
            redis.set(f.owner(257), "b");
            counts = RedisClientOperations.parseFacetIndexCounts(evaluate(redis, request), 257, BUDGET);
            assertEquals(256, counts.counts().size());
            assertTrue(counts.counts().values().stream().allMatch(count -> count == 1L));
        }
    }

    @Test void numericRowsKeepEmptyPresenceAndLaterValueChecksAfterPhaseOverflow() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var f = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            seed(f, 6, "a");
            redis.hset(metadata(f, 1), Map.of("category", "", "amount", ""));
            var request = command(f, 6, "a", false, BUDGET);
            var batch = RedisClientOperations.parseFacetIndexResponse(evaluate(redis, request), 6, "amount", BUDGET)
                    .orElseThrow();
            assertEquals(new RedisClientOperations.FacetIndexRow(0, "", ""), batch.rows().getFirst());
            redis.hdel(metadata(f, 6), "amount");
            assertTrue(assertThrows(JedisDataException.class,
                    () -> evaluate(redis, command(f, 6, "a", false, 1)))
                    .getMessage().contains("Incomplete facet index metadata"));
        }
    }

    @Test void unownedReadsDoNotInspectOwnerValuesOrTypes() {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var f = new RedisT4AggregationRedisTest.Fixture(redis, true)) {
            seed(f, 6, "a");
            for (long id = 1; id <= 6; id++) {
                redis.del(f.owner(id));
                redis.hset(f.owner(id), "invalid", "owner");
            }
            assertPositions(redis, f, 6, null, positions(6));
        }
    }

    private static void assertPositions(JedisPooled redis, RedisT4AggregationRedisTest.Fixture f,
            int size, String tenant, List<Integer> expected) {
        var rows = RedisClientOperations.parseFacetIndexResponse(
                evaluate(redis, command(f, size, tenant, false, BUDGET)), size, "amount", BUDGET).orElseThrow();
        assertEquals(expected, rows.rows().stream().map(RedisClientOperations.FacetIndexRow::candidate).toList());
        var histogram = new LinkedHashMap<String, Long>();
        for (var row : rows.rows()) histogram.merge(row.groupRaw(), 1L, Long::sum);
        var counts = RedisClientOperations.parseFacetIndexCounts(
                evaluate(redis, command(f, size, tenant, true, BUDGET)), size, BUDGET);
        assertEquals(histogram, counts.counts());
        assertTrue(counts.nullCandidates().isEmpty());
        assertEquals(0, redis.getPool().getNumActive());
    }

    private static RedisClientOperations.IndexReadCommand command(RedisT4AggregationRedisTest.Fixture f,
            int size, String tenant, boolean compact, long budget) {
        var ids = LongStream.rangeClosed(1, size).boxed().toList();
        return RedisClientOperations.facetIndexCommand(ids.stream().map(f::entity).toList(),
                ids.stream().map(f::tombstone).toList(), ids.stream().map(id -> metadata(f, id)).toList(),
                tenant == null ? null : ids.stream().map(f::owner).toList(), tenant,
                "category", compact ? null : "amount", budget, compact);
    }

    private static Object evaluate(JedisPooled redis, RedisClientOperations.IndexReadCommand request) {
        return redis.eval(request.script(), request.keys(), request.arguments());
    }

    private static void seed(RedisT4AggregationRedisTest.Fixture f, int size, String tenant) {
        f.seed(LongStream.rangeClosed(1, size).mapToObj(id -> new RedisT4AggregationRedisTest.Row(
                id, tenant, "g" + id % 2, BigDecimal.ONE, "x")).toList());
    }

    private static List<Integer> positions(int size) { return IntStream.range(0, size).boxed().toList(); }

    private static String metadata(RedisT4AggregationRedisTest.Fixture f, long id) {
        return f.keys.indexMetaKey("t4_rows", id);
    }

    private static void corruptMetadata(JedisPooled redis, RedisT4AggregationRedisTest.Fixture f, long id) {
        redis.del(metadata(f, id));
        redis.set(metadata(f, id), "not a hash");
    }
}

package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisDataException;

class RedisFacetPhaseResultTest {
    @Test void defaultPhasePreservesLegacyOptionalOverridesAndUnsupportedAdapters() {
        var unsupported = new Adapter();
        var decline = unsupported.facetIndexCountsPhase(1024, ignored -> { throw new AssertionError(); }, "a", "group");
        assertEquals(RedisClientOperations.FacetPhaseDecline.UNSUPPORTED, decline.decline());
        assertFalse(decline.skipRowsPhase());
        var expected = List.of(new RedisClientOperations.FacetIndexCounts(Map.of("g", 1L), List.of()));
        int[] calls = {0};
        var legacy = new Adapter() {
            @Override public Optional<List<FacetIndexCounts>> facetIndexCounts(int candidates,
                    IntFunction<IndexReadKeys> windows, String tenant, String group) {
                calls[0]++;
                assertEquals(1024, candidates);
                assertEquals("a", tenant);
                assertEquals("group", group);
                return Optional.of(expected);
            }
        };
        assertEquals(expected, legacy.facetIndexCountsPhase(1024, null, "a", "group").batches().orElseThrow());
        assertEquals(1, calls[0]);
    }

    @Test void resultRequiresExactlyOneOutcomeAndFreezesBatches() {
        var source = new ArrayList<String>(List.of("g"));
        var result = RedisClientOperations.FacetPhaseResult.available(source);
        source.clear();
        assertEquals(List.of("g"), result.batches().orElseThrow());
        assertNull(result.decline());
        assertFalse(result.skipRowsPhase());
        assertThrows(UnsupportedOperationException.class, () -> result.batches().orElseThrow().clear());
        assertThrows(IllegalArgumentException.class, () -> new RedisClientOperations.FacetPhaseResult<>(Optional.empty(), null));
        assertThrows(IllegalArgumentException.class, () -> new RedisClientOperations.FacetPhaseResult<>(
                Optional.of(List.of()), RedisClientOperations.FacetPhaseDecline.UNSUPPORTED));
        assertThrows(NullPointerException.class, () -> RedisClientOperations.FacetPhaseResult.declined(null));
    }

    @Test void compactDeclinesDrainAllWindowsAndDistinguishReasonsForBothKeyPaths() {
        for (boolean binary : List.of(false, true)) {
            for (var reason : List.of(RedisClientOperations.FacetPhaseDecline.EVAL_COMMAND_DENIED,
                    RedisClientOperations.FacetPhaseDecline.SOFT_BYTE_BUDGET)) {
                for (int position : List.of(0, 1, 2)) {
                    try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                        var replies = new ArrayList<Object>(List.of(reply(), reply(), reply()));
                        replies.set(position, reason == RedisClientOperations.FacetPhaseDecline.EVAL_COMMAND_DENIED
                                ? denied() : marker());
                        pool.connection.reply(replies);
                        var result = phase(pool, binary);
                        assertEquals(reason, result.decline());
                        assertTrue(result.batches().isEmpty());
                        assertTrue(result.skipRowsPhase());
                        assertEquals(List.of(3), pool.connection.requestedReplies);
                        assertEquals(3, pool.connection.sends);
                        assertEquals(1, pool.connection.reads);
                        assertEquals(reason == RedisClientOperations.FacetPhaseDecline.EVAL_COMMAND_DENIED ? 1 : 0,
                                pool.brokenReturns);
                        assertEquals(reason == RedisClientOperations.FacetPhaseDecline.EVAL_COMMAND_DENIED ? 0 : 1,
                                pool.healthyReturns);
                    }
                }
            }
        }
    }

    @Test void declinesNeverMaskDataKeyAclNestedErrorsOrMalformedReplies() {
        for (boolean binary : List.of(false, true)) {
            for (Object decline : List.of(denied(), marker())) {
                for (Object poison : List.of(new JedisDataException("WRONGTYPE"),
                        new JedisAccessControlException("NOPERM this user has no permissions to access one of the keys used as arguments"),
                        List.of(List.of(List.of(new JedisDataException("nested"), 1L)), List.of()),
                        List.of(bytes("cachedb-facet-phase-budget-v1"), 511L, quota()))) {
                    for (int position : List.of(0, 1, 2)) {
                        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                            var replies = new ArrayList<Object>(List.of(decline, decline, decline));
                            replies.set(position, poison);
                            pool.connection.reply(replies);
                            assertThrows(RuntimeException.class, () -> phase(pool, binary));
                            assertEquals(List.of(3), pool.connection.requestedReplies);
                            assertEquals(0, pool.healthyReturns);
                            assertEquals(1, pool.brokenReturns);
                        }
                    }
                }
            }
        }
    }

    @Test void successAndUnsupportedDoNotSuppressRowsAndDenialWinsOverSoftBudget() {
        for (boolean binary : List.of(false, true)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(List.of(reply(), reply(), reply()));
                var result = phase(pool, binary);
                assertEquals(3, result.batches().orElseThrow().size());
                assertNull(result.decline());
                assertFalse(result.skipRowsPhase());
            }
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(List.of(marker(), reply(), denied()));
                assertEquals(RedisClientOperations.FacetPhaseDecline.EVAL_COMMAND_DENIED, phase(pool, binary).decline());
            }
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            var result = RedisStandaloneIndexSnapshot.facetCountPhase(pool, 512,
                    ignored -> { throw new AssertionError(); }, "a", "group");
            assertEquals(RedisClientOperations.FacetPhaseDecline.UNSUPPORTED, result.decline());
            assertFalse(result.skipRowsPhase());
            assertEquals(0, pool.borrows);
        }
    }

    private static RedisClientOperations.FacetPhaseResult<RedisClientOperations.FacetIndexCounts> phase(
            RedisStandaloneIndexSnapshotTest.FakePool pool, boolean binary) {
        if (binary) return RedisStandaloneIndexSnapshot.facetCountPhase(pool,
                new RedisBinaryFacetKeys(new RedisKeyStrategy(), "row",
                        IntStream.range(0, 1536).mapToObj(Integer::toString).toList(), "a"), "a", "group");
        return RedisStandaloneIndexSnapshot.facetCountPhase(pool, 1536,
                start -> new RedisClientOperations.IndexReadKeys(keys("entity:", start), keys("tomb:", start),
                        keys("meta:", start), keys("owner:", start)), "a", "group");
    }

    private static List<String> keys(String prefix, int start) {
        return IntStream.range(start, start + 512).mapToObj(i -> prefix + i).toList();
    }
    private static List<Object> reply() { return List.of(List.of(List.of(bytes("g"), 1L)), List.of()); }
    private static List<Object> marker() { return List.of(bytes("cachedb-facet-phase-budget-v1"), 512L, quota()); }
    private static long quota() { return (RedisStandaloneIndexSnapshot.MAX_BYTES - 64L * (1536 + 3)) / 9; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JedisAccessControlException denied() {
        return new JedisAccessControlException("NOPERM this user has no permissions to run the 'eval' command");
    }
    private static class Adapter implements RedisClientOperations {
        public UnifiedJedis commands() { throw new AssertionError("No transport expected"); }
        public boolean clustered() { return false; }
    }
}

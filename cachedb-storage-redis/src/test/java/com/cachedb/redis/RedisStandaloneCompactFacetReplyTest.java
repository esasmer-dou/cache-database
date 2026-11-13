package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

class RedisStandaloneCompactFacetReplyTest {
    @Test void compactRawRepliesUseOneDrainedPhaseOnCustomConnections() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            var replies = new ArrayList<Object>();
            for (int start = 0; start < 10_000; start += 512) replies.add(result(Math.min(512, 10_000 - start)));
            pool.connection.reply(replies);
            var batches = facets(pool, 10_000).orElseThrow();
            assertEquals(20, batches.size());
            assertEquals(10_000L, batches.stream().mapToLong(batch -> batch.counts().get("\u00c7\u0131\u011f")).sum());
            assertEquals(1, pool.borrows);
            assertEquals(1, pool.connection.reads);
            assertEquals(List.of(20), pool.connection.requestedReplies);
            assertEquals(20, pool.connection.sends);
            assertTrue(pool.connection.names().stream().allMatch("EVAL"::equals));
            assertEquals(1, pool.healthyReturns);
            assertEquals(0, pool.brokenReturns);
            assertThrows(UnsupportedOperationException.class, () -> batches.clear());
        }
    }

    @Test void nullCandidatesAndEmptyCompactRepliesRemainPresent() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(List.of(List.of(List.of(bytes("g"), 2L)), List.of(0L, 511L)), result(0)));
            var batches = facets(pool, 1024).orElseThrow();
            assertEquals(Map.of("g", 2L), batches.getFirst().counts());
            assertEquals(List.of(0, 511), batches.getFirst().nullCandidates());
            assertTrue(batches.getLast().counts().isEmpty());
        }
    }

    @Test void exactRawOrCustomStringQuotaMarkerDiscardsAllPartialBatches() {
        for (boolean raw : List.of(false, true)) {
            for (int markerAt : List.of(0, 1, 2)) {
                try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                    var replies = new ArrayList<Object>(List.of(result(1), result(1), result(1)));
                    replies.set(markerAt, marker(512, quota(1536), raw));
                    pool.connection.reply(replies);
                    assertTrue(facets(pool, 1536).isEmpty());
                    assertEquals(List.of(3), pool.connection.requestedReplies);
                    assertEquals(1, pool.healthyReturns);
                    assertEquals(0, pool.brokenReturns);
                }
            }
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(result(1), marker(1, quota(513), true)));
            assertTrue(facets(pool, 513).isEmpty());
        }
    }

    @Test void directEvalDenialDrainsThePhaseAndReturnsBrokenWithoutPartialResults() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(result(1), denied(), marker(512, quota(1536), true)));
            assertTrue(facets(pool, 1536).isEmpty());
            assertEquals(1, pool.borrows);
            assertEquals(List.of(3), pool.connection.requestedReplies);
            assertEquals(0, pool.healthyReturns);
            assertEquals(1, pool.brokenReturns);
        }
    }

    @Test void denialAndQuotaMarkerNeverHidePoisonInAnyWindow() {
        for (Object rejected : List.of(denied(), marker(512, quota(1536), true))) {
            for (Object poison : List.of(new JedisDataException("WRONGTYPE"),
                    List.of(List.of(List.of("already decoded", 1L)), List.of()),
                    List.of(List.of(List.of(bytes("g"), 0L)), List.of()),
                    List.of(List.of(), List.of(2L, 1L)),
                    List.of(List.of(List.of(new JedisDataException("nested poison"), 1L)), List.of()))) {
                for (int failureAt : List.of(0, 1, 2)) {
                    try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                        var replies = new ArrayList<Object>(List.of(rejected, rejected, rejected));
                        replies.set(failureAt, poison);
                        pool.connection.reply(replies);
                        assertThrows(RuntimeException.class, () -> facets(pool, 1536));
                        assertEquals(1, pool.borrows);
                        assertEquals(0, pool.healthyReturns);
                        assertEquals(1, pool.brokenReturns);
                    }
                }
            }
        }
    }

    @Test void forgedMarkersWrongReplyCountAndWeightedBudgetPoisonNeverBecomeFallback() {
        long budget = quota(1024);
        for (Object invalid : List.of(marker(511, budget, true), marker(512, budget + 1, true),
                List.of(bytes("cachedb-facet-phase-budget-v1"), "512", budget),
                List.of(bytes("cachedb-facet-phase-budget-v1"), 512L, (double) budget),
                List.of(bytes("cachedb-facet-phase-budget-v2"), 512L, budget),
                List.of(bytes("cachedb-facet-phase-budget-v1"), 512L, budget, 0L))) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(List.of(marker(512, budget, true), invalid));
                assertThrows(IllegalStateException.class, () -> facets(pool, 1024));
                assertEquals(0, pool.healthyReturns);
                assertEquals(1, pool.brokenReturns);
            }
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            var replies = new ArrayList<Object>();
            for (int start = 0; start < 10_000; start += 512)
                replies.add(marker(Math.min(512, 10_000 - start), quota(10_000), true));
            replies.set(1, List.of(List.of(List.of(bytes("x".repeat(4096)), 512L)), List.of()));
            pool.connection.reply(replies);
            assertThrows(IllegalStateException.class, () -> facets(pool, 10_000));
            assertEquals(0, pool.healthyReturns);
            assertEquals(1, pool.brokenReturns);
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(result(0)));
            assertThrows(IllegalStateException.class, () -> facets(pool, 1024));
            assertEquals(1, pool.brokenReturns);
        }
    }

    @Test void keyWrappedAndNestedAclErrorsAreNotCommandFallback() {
        var wrapped = new JedisAccessControlException(denied().getMessage(), new IllegalStateException("wrapped"));
        var suppressed = denied();
        suppressed.addSuppressed(new IllegalStateException("suppressed"));
        for (Object error : List.of(new JedisAccessControlException("NOPERM No permissions to access a key"),
                wrapped, suppressed, List.of(denied()))) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(List.of(marker(512, quota(1024), true), error));
                assertThrows(RuntimeException.class, () -> facets(pool, 1024));
                assertEquals(0, pool.healthyReturns);
                assertEquals(1, pool.brokenReturns);
            }
        }
    }

    @Test void transportCancellationAndReturnFailuresAreNotCompactFallback() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.failureAtSend = 2;
            assertThrows(JedisConnectionException.class, () -> facets(pool, 1024));
            assertEquals(1, pool.brokenReturns);
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.failureAtRead = 1;
            assertThrows(JedisConnectionException.class, () -> facets(pool, 1024));
            assertEquals(1, pool.brokenReturns);
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(List.of(result(1), marker(512, quota(1024), true)));
            pool.failReturn = true;
            assertThrows(JedisConnectionException.class, () -> facets(pool, 1024));
        }
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            Thread.currentThread().interrupt();
            try { assertThrows(CancellationException.class, () -> facets(pool, 1024)); }
            finally { Thread.interrupted(); }
            assertEquals(0, pool.borrows);
        }
    }

    @Test void genericFacetRepliesStillUseTheExistingDecodedRowPath() {
        for (String valueField : Arrays.asList(null, "amount")) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                Object value = valueField == null ? null : bytes("12.5");
                var reply = List.of(List.of(0L), List.of(Arrays.asList(bytes("\u00c7\u0131\u011f"), value)));
                pool.connection.reply(valueField == null ? List.of(reply, reply) : List.of(reply, reply, reply));
                var batches = RedisStandaloneIndexSnapshot.facetIndexEvals(pool, 513,
                        start -> keys(start, Math.min(valueField == null ? 512 : 256, 513 - start)),
                        "a", "group", valueField);
                assertTrue(batches.isPresent());
                var row = batches.orElseThrow().getFirst().rows().getFirst();
                assertEquals("\u00c7\u0131\u011f", row.groupRaw());
                assertEquals(valueField == null ? null : "12.5", row.valueRaw());
                assertEquals(1, pool.healthyReturns);
            }
        }
    }

    private static Optional<List<RedisClientOperations.FacetIndexCounts>> facets(
            RedisStandaloneIndexSnapshotTest.FakePool pool, int size) {
        return RedisStandaloneIndexSnapshot.facetCountEvals(pool, size,
                start -> keys(start, Math.min(512, size - start)), "a", "group");
    }

    private static RedisClientOperations.IndexReadKeys keys(int start, int size) {
        return new RedisClientOperations.IndexReadKeys(keys("entity:", start, size), keys("tomb:", start, size),
                keys("meta:", start, size), keys("owner:", start, size));
    }

    private static List<String> keys(String prefix, int start, int size) {
        return IntStream.range(start, start + size).mapToObj(i -> prefix + i).toList();
    }

    private static List<Object> result(int count) {
        return List.of(count == 0 ? List.of() : List.of(List.of(bytes("\u00c7\u0131\u011f"), (long) count)), List.of());
    }

    private static List<Object> marker(long size, long budget, boolean raw) {
        String token = "cachedb-facet-phase-budget-v1";
        return List.of(raw ? bytes(token) : token, size, budget);
    }

    private static long quota(int candidates) {
        int count = (candidates - 1) / 512 + 1;
        return (RedisStandaloneIndexSnapshot.MAX_BYTES - 64L * (candidates + count)) / (3L * count);
    }

    private static JedisAccessControlException denied() {
        return new JedisAccessControlException("NOPERM this user has no permissions to run the 'eval' command");
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}

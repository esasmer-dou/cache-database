package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisDataException;

class RedisStandaloneBinaryFacetTest {
    @Test void compactBinaryPhaseRetainsTwentyCommandsAndAllDeclaredKeys() {
        for (String tenant : Arrays.asList(null, "a")) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                var replies = new ArrayList<Object>();
                for (int start = 0; start < 10_000; start += 512) replies.add(result(Math.min(512, 10_000 - start)));
                pool.connection.reply(replies);
                var batches = RedisStandaloneIndexSnapshot.facetCountEvals(pool, keys(10_000, tenant), tenant, "g").orElseThrow();
                assertEquals(10_000L, batches.stream().mapToLong(batch -> batch.counts().get("g")).sum());
                assertEquals(20, batches.size());
                assertEquals(1, pool.borrows);
                assertEquals(List.of(20), pool.connection.requestedReplies);
                assertEquals(1, pool.healthyReturns);
                var commands = pool.connection.phases.getFirst();
                for (int window = 0; window < 20; window++) {
                    int start = window * 512, size = Math.min(512, 10_000 - start);
                    var expected = RedisBinaryFacetKeysTest.canonical(new RedisKeyStrategy(), "ns",
                            IntStream.range(start, start + size).mapToObj(Integer::toString).toList(),
                            tenant, "g", null, quota(10_000, 512), true);
                    assertEquals(RedisBinaryFacetKeysTest.arguments(expected).stream()
                            .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).toList(), commands.get(window));
                }
            }
        }
    }

    @Test void genericBinaryPhasesPreserveDecodedRowsAndValueWindowSize() {
        for (String valueField : Arrays.asList(null, "amount")) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                Object value = valueField == null ? null : bytes("12.5");
                var reply = List.of(List.of(0L), List.of(Arrays.asList(bytes("g"), value)));
                pool.connection.reply(valueField == null ? List.of(reply, reply) : List.of(reply, reply, reply));
                var batches = RedisStandaloneIndexSnapshot.facetIndexEvals(pool, keys(513, "a"), "a", "g", valueField).orElseThrow();
                assertEquals(valueField == null ? 2 : 3, batches.size());
                assertEquals(valueField == null ? null : "12.5", batches.getFirst().rows().getFirst().valueRaw());
                assertEquals(1, pool.healthyReturns);
            }
        }
    }

    @Test void invalidLaterKeyAndTenantAreRejectedBeforeBorrowOrSend() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            var ids = new ArrayList<>(java.util.Collections.nCopies(1024, "id"));
            ids.set(700, "x".repeat(16_385));
            var keys = new RedisBinaryFacetKeys(new RedisKeyStrategy(), "ns", ids, "a");
            assertThrows(IllegalArgumentException.class,
                    () -> RedisStandaloneIndexSnapshot.facetCountEvals(pool, keys, "a", "g"));
            assertThrows(IllegalArgumentException.class,
                    () -> RedisStandaloneIndexSnapshot.facetCountEvals(pool, keys(1024, "a"), "b", "g"));
            assertEquals(0, pool.borrows);
            assertEquals(0, pool.connection.sends);
        }
    }

    @Test void completeEncodedPhaseLimitIsCheckedBeforeBorrowOrSend() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            var keys = new RedisBinaryFacetKeys(new RedisKeyStrategy(), "ns",
                    java.util.Collections.nCopies(10_000, "x".repeat(1000)), null);
            assertTrue(RedisStandaloneIndexSnapshot.facetCountEvals(pool, keys, null, "g").isEmpty());
            assertEquals(0, pool.borrows);
            assertEquals(0, pool.connection.sends);
        }
    }

    @Test void denialAndQuotaMarkersDoNotHidePoisonOrReturnPartialResults() {
        var denied = new JedisAccessControlException("NOPERM this user has no permissions to run the 'eval' command");
        var marker = List.of(bytes("cachedb-facet-phase-budget-v1"), 512L, quota(1536, 512));
        for (Object rejection : List.of(denied, marker)) {
            try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                pool.connection.reply(List.of(result(1), rejection, result(1)));
                assertTrue(RedisStandaloneIndexSnapshot.facetCountEvals(pool, keys(1536, "a"), "a", "g").isEmpty());
                assertEquals(List.of(3), pool.connection.requestedReplies);
                assertEquals(rejection == denied ? 1 : 0, pool.brokenReturns);
            }
            for (int at = 0; at < 3; at++) {
                try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
                    var replies = new ArrayList<Object>(List.of(rejection, rejection, rejection));
                    replies.set(at, new JedisDataException("WRONGTYPE"));
                    pool.connection.reply(replies);
                    assertThrows(JedisDataException.class,
                            () -> RedisStandaloneIndexSnapshot.facetCountEvals(pool, keys(1536, "a"), "a", "g"));
                    assertEquals(1, pool.brokenReturns);
                    assertEquals(0, pool.healthyReturns);
                }
            }
        }
    }

    @Test void cancellationBeforePreparationDoesNotBorrow() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(CancellationException.class,
                        () -> RedisStandaloneIndexSnapshot.facetCountEvals(pool, keys(1024, null), null, "g"));
            } finally { Thread.interrupted(); }
            assertEquals(0, pool.borrows);
        }
    }

    private static RedisBinaryFacetKeys keys(int size, String tenant) {
        return new RedisBinaryFacetKeys(new RedisKeyStrategy(), "ns",
                IntStream.range(0, size).mapToObj(Integer::toString).toList(), tenant);
    }

    private static List<Object> result(int count) {
        return List.of(List.of(List.of(bytes("g"), (long) count)), List.of());
    }

    private static long quota(int candidates, int rows) {
        int count = (candidates - 1) / rows + 1;
        return (RedisStandaloneIndexSnapshot.MAX_BYTES - 64L * (candidates + count)) / (3L * count);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}

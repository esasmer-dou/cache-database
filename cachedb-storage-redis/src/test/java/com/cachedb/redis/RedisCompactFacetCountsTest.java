package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

class RedisCompactFacetCountsTest {
    @Test void compactCountsRetainWeightsSentinelPositionsAndImmutableResults() {
        var result = RedisClientOperations.parseFacetIndexCounts(
                List.of(List.of(List.of("", 2L), List.of("\u00c7\u0131\u011f", 3L)), List.of(1L, 6L)), 8, 1024);
        assertEquals(Map.of("", 2L, "\u00c7\u0131\u011f", 3L), result.counts());
        assertEquals(List.of(1, 6), result.nullCandidates());
        assertThrows(UnsupportedOperationException.class, () -> result.counts().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.nullCandidates().clear());
        var empty = RedisClientOperations.parseFacetIndexCounts(List.of(List.of(), List.of()), 8, 1);
        assertTrue(empty.counts().isEmpty()); assertTrue(empty.nullCandidates().isEmpty());
    }

    @Test void malformedWeightsGroupsPositionsAndBudgetsFailClosed() {
        var invalid = new ArrayList<Object>(Arrays.asList(null, "bad", List.of(), List.of(List.of())));
        for (Object count : Arrays.asList(null, -1L, 0L, 9L, 1, 1.5, "1"))
            invalid.add(List.of(List.of(Arrays.asList("g", count)), List.of()));
        for (Object group : Arrays.asList(null, 1L, "__NULL__", "x".repeat(4097)))
            invalid.add(List.of(List.of(Arrays.asList(group, 1L)), List.of()));
        invalid.add(List.of(List.of(List.of("g", 1L), List.of("g", 1L)), List.of()));
        invalid.add(List.of(List.of(List.of("g", 8L)), List.of(0L)));
        invalid.add(List.of(List.of(List.of("g", 5L), List.of("h", 4L)), List.of()));
        for (List<?> positions : List.of(List.of(-1L), List.of(8L), List.of(1L, 1L), List.of(2L, 1L), List.of(1.5)))
            invalid.add(List.of(List.of(), positions));
        for (Object reply : invalid)
            assertThrows(IllegalStateException.class, () -> RedisClientOperations.parseFacetIndexCounts(reply, 8, 1024));
        assertThrows(IllegalStateException.class, () -> RedisClientOperations.parseFacetIndexCounts(
                List.of(List.of(List.of("g", 8L)), List.of()), 8, 143));
        var groups = new ArrayList<List<?>>();
        for (int i = 0; i < 257; i++) groups.add(List.of("g" + i, 1L));
        assertThrows(IllegalStateException.class, () -> RedisClientOperations.parseFacetIndexCounts(
                List.of(groups, List.of()), 512, 1_000_000));
    }

    @Test void defaultCapabilityDoesNotTouchCustomTransportOrClusterWindows() {
        for (boolean cluster : List.of(false, true)) {
            var client = new RedisClientOperations() {
                public boolean clustered() { return cluster; }
                public UnifiedJedis commands() { throw new AssertionError("No transport expected"); }
            };
            assertTrue(client.facetIndexCounts(1024, ignored -> { throw new AssertionError("No window expected"); },
                    "a", "category").isEmpty());
        }
    }
}

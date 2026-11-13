package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import redis.clients.jedis.BuilderFactory;
import redis.clients.jedis.util.SafeEncoder;

@Isolated("Temporarily changes the global Jedis charset")
class RedisCompactFacetReplyDecoderTest {
    @Test void legacyCharsetDecodingAndBinaryEligibilityRestoreGlobalCharset() {
        var original = SafeEncoder.DEFAULT_CHARSET;
        try {
            SafeEncoder.DEFAULT_CHARSET = StandardCharsets.ISO_8859_1;
            var reply = List.of(List.of(List.of(SafeEncoder.encode("caf\u00e9"), 2L)), List.of());
            var decoded = RedisCompactFacetReplyDecoder.parse(reply, 2, 48);
            assertEquals(Map.of("caf\u00e9", 2L), decoded.counts());
            assertEquals(reference(reply, 2, 48), decoded);
            assertThrows(IllegalStateException.class, () -> RedisCompactFacetReplyDecoder.parse(reply, 2, 47));
            assertTrue(RedisCompactFacetReplyDecoder.isBudgetMarker(
                    "cachedb-facet-phase-budget-v1".getBytes(StandardCharsets.US_ASCII)));
            assertFalse(RedisBinaryFacetKeys.supports(new RedisKeyStrategy()));
            assertFalse(RedisBinaryFacetKeys.supports(new ClusterHashTagKeyStrategy("p", "scope")));
            assertThrows(IllegalArgumentException.class,
                    () -> new RedisBinaryFacetKeys(new RedisKeyStrategy(), "ns", List.of("id"), null));
            SafeEncoder.DEFAULT_CHARSET = StandardCharsets.UTF_8;
            assertTrue(RedisBinaryFacetKeys.supports(new RedisKeyStrategy()));
            assertTrue(RedisBinaryFacetKeys.supports(new ClusterHashTagKeyStrategy("p", "scope")));
        } finally {
            SafeEncoder.DEFAULT_CHARSET = original;
        }
        assertSame(original, SafeEncoder.DEFAULT_CHARSET);
    }

    @Test void rawGroupsRetainWeightsSentinelPositionsAndImmutableResults() {
        var reply = List.of(List.of(pair("", 2L), pair("\u00c7\u0131\u011f", 3L)), List.of(1L, 6L));
        var result = RedisCompactFacetReplyDecoder.parse(reply, 8, 1024);
        assertEquals(Map.of("", 2L, "\u00c7\u0131\u011f", 3L), result.counts());
        assertEquals(List.of(1, 6), result.nullCandidates());
        assertEquals(reference(reply, 8, 1024), result);
        assertThrows(UnsupportedOperationException.class, () -> result.counts().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.nullCandidates().clear());
        var empty = RedisCompactFacetReplyDecoder.parse(List.of(List.of(), List.of()), 8, 1);
        assertTrue(empty.counts().isEmpty());
        assertTrue(empty.nullCandidates().isEmpty());
    }

    @Test void utf8DecodingMatchesThePreviousJedisPathIncludingReplacementCharacters() {
        for (byte[] group : List.of(bytes("ascii\u0000\r\n"), bytes("\u00c7\u0131\u011f"), bytes("\u4e00"),
                bytes("\ud83d\ude00"), new byte[] {(byte) 0xc3}, new byte[] {(byte) 0x80},
                new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80})) {
            var reply = List.of(List.of(List.of(group, 2L)), List.of());
            long budget = 2L * (2L * new String(group, StandardCharsets.UTF_8).length() + 16);
            assertEquals(reference(reply, 2, budget), RedisCompactFacetReplyDecoder.parse(reply, 2, budget));
            assertThrows(IllegalStateException.class, () -> RedisCompactFacetReplyDecoder.parse(reply, 2, budget - 1));
        }
    }

    @Test void byteDistinctGroupsThatDecodeIdenticallyStillFailAsDuplicates() {
        var reply = List.of(List.of(List.of(new byte[] {(byte) 0x80}, 1L), pair("\ufffd", 1L)), List.of());
        assertThrows(IllegalStateException.class, () -> reference(reply, 2, 1024));
        assertThrows(IllegalStateException.class, () -> RedisCompactFacetReplyDecoder.parse(reply, 2, 1024));
    }

    @Test void rawGroupAndLongWeightTypesAndBothTupleAritiesAreStrict() {
        var invalid = new ArrayList<Object>(Arrays.asList(null, "bad", List.of(), List.of(List.of()),
                List.of(List.of(), List.of(), List.of()), List.of("bad", List.of()), List.of(List.of(), "bad")));
        for (Object count : Arrays.asList(null, -1L, 0L, 9L, 1, 1.0, "1", bytes("1")))
            invalid.add(List.of(List.of(Arrays.asList(bytes("g"), count)), List.of()));
        for (Object group : Arrays.asList(null, "g", 1L, bytes("__NULL__")))
            invalid.add(List.of(List.of(Arrays.asList(group, 1L)), List.of()));
        invalid.add(List.of(List.of(List.of(bytes("g"))), List.of()));
        invalid.add(List.of(List.of(List.of(bytes("g"), 1L, 0L)), List.of()));
        invalid.add(List.of(List.of("not a tuple"), List.of()));
        for (Object reply : invalid)
            assertThrows(IllegalStateException.class, () -> RedisCompactFacetReplyDecoder.parse(reply, 8, 1024));
    }

    @Test void duplicateGroupsOverlappingTotalsAndInvalidNullPositionsFailClosed() {
        var invalid = new ArrayList<Object>();
        invalid.add(List.of(List.of(pair("g", 1L), pair("g", 1L)), List.of()));
        invalid.add(List.of(List.of(pair("g", 8L)), List.of(0L)));
        invalid.add(List.of(List.of(pair("g", 5L), pair("h", 4L)), List.of()));
        invalid.add(List.of(List.of(), List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)));
        for (List<?> positions : List.of(List.of(-1L), List.of(8L), List.of(1L, 1L), List.of(2L, 1L),
                List.of(1.5), List.of(Double.NaN), List.of("1"), Arrays.asList((Object) null)))
            invalid.add(List.of(List.of(), positions));
        for (Object reply : invalid) {
            assertThrows(IllegalStateException.class, () -> reference(reply, 8, 1024));
            assertThrows(IllegalStateException.class, () -> RedisCompactFacetReplyDecoder.parse(reply, 8, 1024));
        }
        var numericPositions = List.of(List.of(), List.of(0, 2.0, 7L));
        assertEquals(reference(numericPositions, 8, 1), RedisCompactFacetReplyDecoder.parse(numericPositions, 8, 1));
    }

    @Test void weightedBudgetIncludesMultiplicityAndEveryGroup() {
        var reply = List.of(List.of(pair("g", 5L), pair("\ud83d\ude00", 3L)), List.of());
        long budget = 5L * 18 + 3L * 20;
        assertEquals(reference(reply, 8, budget), RedisCompactFacetReplyDecoder.parse(reply, 8, budget));
        assertThrows(IllegalStateException.class, () -> RedisCompactFacetReplyDecoder.parse(reply, 8, budget - 1));
        assertThrows(IllegalStateException.class, () -> RedisCompactFacetReplyDecoder.parse(reply, 8, 38));
    }

    @Test void groupLengthUsesUtf16AndGroupCapRemains256() {
        for (String group : List.of("x".repeat(4096), "\u4e00".repeat(4096), "\ud83d\ude00".repeat(2048))) {
            var reply = List.of(List.of(pair(group, 1L)), List.of());
            assertEquals(reference(reply, 1, 8208), RedisCompactFacetReplyDecoder.parse(reply, 1, 8208));
            var oversized = List.of(List.of(pair(group + "x", 1L)), List.of());
            assertThrows(IllegalStateException.class, () -> RedisCompactFacetReplyDecoder.parse(oversized, 1, 1_000_000));
        }
        var pairs = new ArrayList<List<?>>();
        for (int i = 0; i < 256; i++) pairs.add(pair("g" + i, 1L));
        var reply = List.of(pairs, List.of());
        assertEquals(256, RedisCompactFacetReplyDecoder.parse(reply, 512, 1_000_000).counts().size());
        assertEquals(reference(reply, 512, 1_000_000), RedisCompactFacetReplyDecoder.parse(reply, 512, 1_000_000));
        pairs.add(pair("g256", 1L));
        assertThrows(IllegalStateException.class, () -> RedisCompactFacetReplyDecoder.parse(reply, 512, 1_000_000));
        assertThrows(IllegalStateException.class, () -> RedisCompactFacetReplyDecoder.parse(
                List.of(List.of(pair("a", 1L), pair("b", 1L)), List.of()), 1, 1024));
    }

    private static RedisClientOperations.FacetIndexCounts reference(Object reply, int size, long budget) {
        return RedisClientOperations.parseFacetIndexCounts(BuilderFactory.AGGRESSIVE_ENCODED_OBJECT.build(reply), size, budget);
    }

    private static List<Object> pair(String group, long count) { return List.of(bytes(group), count); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}

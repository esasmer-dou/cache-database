package com.reactor.cachedb.core.repository;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisLexicalWindowCursorTest {
    @Test void distinctWireVersionNeverReinterpretsTypedCursors() {
        String typed = WindowCursor.encode(Map.of("score", 7, "id", 2L), "route");
        String redis = WindowCursor.encodeRedisLexical(7, "2", "route");
        assertEquals(Map.of("score", 7, "id", 2L), WindowCursor.decode(typed, "route"));
        assertEquals(new WindowCursor.RedisLexicalPosition(7, "2"), WindowCursor.decodeRedisLexical(redis, "route"));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLexical(typed, "route"));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decode(redis, "route"));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decode(redis));
        assertThrows(CursorContractMismatchException.class, () -> WindowCursor.decodeRedisLexical(redis, "other-route"));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLexical(
                WindowCursor.encode(Map.of("redis_score", 7d, "redis_id", "2")), "route"));
    }

    @Test void boundsUnicodeAndNonFiniteScoresAreRejectedBeforeUse() {
        String id = "\uD83D\uDE00".repeat(1024);
        assertEquals(id, WindowCursor.decodeRedisLexical(WindowCursor.encodeRedisLexical(1, id, "x"), "x").id());
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.encodeRedisLexical(1, id + "x", "x"));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.encodeRedisLexical(1, "\uD800", "x"));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.encodeRedisLexical(1, "", "x"));
        for (double score : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> WindowCursor.encodeRedisLexical(score, "1", "x"));
        assertEquals(0L, Double.doubleToRawLongBits(WindowCursor.decodeRedisLexical(
                WindowCursor.encodeRedisLexical(-0d, "1", "x"), "x").score()));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLexical("x".repeat(8193), "x"));
        String invalidUtf8 = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {(byte) 0xc3, 0x28});
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLexical(invalidUtf8, "x"));
    }

    @Test void exactScoreAndIdTypesAndFieldSetAreRequired() {
        String token = WindowCursor.encodeRedisLexical(1, "10", "x");
        Map<String, String> fields = new LinkedHashMap<>(LengthPrefixedPayloadCodec.decode(new String(
                Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)));
        fields.put("redis_score", "L:1");
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLexical(wire(fields), "x"));
        fields.put("redis_score", "D:1.0");
        fields.put("redis_id", "L:10");
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLexical(wire(fields), "x"));
        fields.put("redis_id", "S:10");
        fields.put("extra", "S:value");
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLexical(wire(fields), "x"));
    }

    private static String wire(Map<String, String> values) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                LengthPrefixedPayloadCodec.encode(values).getBytes(StandardCharsets.UTF_8));
    }
}

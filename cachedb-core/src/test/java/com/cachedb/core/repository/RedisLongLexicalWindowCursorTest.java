package com.reactor.cachedb.core.repository;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.codec.LengthPrefixedPayloadCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class RedisLongLexicalWindowCursorTest {
    @Test void exactLongEndpointsAndAdjacentCollapsedScoresRoundTripWithoutDouble() {
        for (long value : new long[] {Long.MIN_VALUE, -9007199254740993L, -9007199254740991L, 0,
                1800000000000L, 9007199254740991L, 9007199254740992L, 9007199254740993L, Long.MAX_VALUE}) {
            String token = WindowCursor.encodeRedisLongLexical(value, "10", "typed-route");
            assertEquals(new WindowCursor.RedisLongPosition(value, "10"),
                    WindowCursor.decodeRedisLongLexical(token, "typed-route"));
            var fields = fields(token);
            assertEquals("4-redis-long-lex-id", fields.get("cachedb_cursor_version"));
            assertEquals("L:" + value, fields.get("redis_long"));
            assertFalse(fields.containsKey("redis_score"));
        }
        assertNotEquals(WindowCursor.encodeRedisLongLexical(9007199254740992L, "1", "x"),
                WindowCursor.encodeRedisLongLexical(9007199254740993L, "1", "x"));
    }

    @Test void legacyTypedAndV3TokensAreNotReinterpretedAndRemainByteStable() {
        Map<String,Object> values = Map.of("score", 9007199254740993L);
        String legacy = WindowCursor.encode(values);
        String typed = WindowCursor.encode(values, "x");
        assertEquals("NTpzY29yZTE4Okw6OTAwNzE5OTI1NDc0MDk5Mw", legacy);
        assertEquals("MjI6Y2FjaGVkYl9jdXJzb3JfdmVyc2lvbjE6MjIzOmNhY2hlZGJfY3Vyc29yX2NvbnRyYWN0NDM6TFhFV1FyY21zRVFCWW55cC02d3k5Y2hURDdHUVBNVGJBaVdIRjVJYVNJRTU6c2NvcmUxODpMOjkwMDcxOTkyNTQ3NDA5OTM", typed);
        String v3 = WindowCursor.encodeRedisLexical(42, "2", "x");
        String v4 = WindowCursor.encodeRedisLongLexical(42, "2", "x");
        assertEquals(values, WindowCursor.decode(legacy));
        assertEquals(values, WindowCursor.decode(typed, "x"));
        assertEquals(new WindowCursor.RedisLexicalPosition(42, "2"), WindowCursor.decodeRedisLexical(v3, "x"));
        assertEquals(legacy, WindowCursor.encode(WindowCursor.decode(legacy)));
        assertEquals(typed, WindowCursor.encode(WindowCursor.decode(typed, "x"), "x"));
        assertEquals(v3, WindowCursor.encodeRedisLexical(WindowCursor.decodeRedisLexical(v3, "x").score(), "2", "x"));
        for (String token : List.of(legacy, typed, v3))
            assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLongLexical(token, "x"));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decode(v4));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decode(v4, "x"));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLexical(v4, "x"));
        assertThrows(CursorContractMismatchException.class, () -> WindowCursor.decodeRedisLongLexical(v4, "other"));
    }

    @Test void wrongScoreIdExtraFieldsUnicodeAndBoundsFailBeforeUse() {
        String token = WindowCursor.encodeRedisLongLexical(42, "10", "x");
        var fields = fields(token);
        for (String invalid : List.of("D:42.0", "I:42", "S:42", "L:9223372036854775808")) {
            fields.put("redis_long", invalid);
            assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLongLexical(wire(fields), "x"));
        }
        fields.put("redis_long", "L:42"); fields.put("redis_id", "L:10");
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLongLexical(wire(fields), "x"));
        fields.put("redis_id", "S:10"); fields.put("extra", "S:x");
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLongLexical(wire(fields), "x"));
        String id = "\uD83D\uDE00".repeat(1024);
        assertEquals(id, WindowCursor.decodeRedisLongLexical(WindowCursor.encodeRedisLongLexical(0, id, "x"), "x").id());
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.encodeRedisLongLexical(0, id + "x", "x"));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.encodeRedisLongLexical(0, "\uD800", "x"));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.encodeRedisLongLexical(0, "", "x"));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLongLexical("x".repeat(8193), "x"));
        String duplicated = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8) + "10:redis_long4:L:43";
        String duplicateToken = Base64.getUrlEncoder().withoutPadding().encodeToString(duplicated.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLongLexical(duplicateToken, "x"));
        String invalidUtf8 = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{(byte)0xc3,0x28});
        assertThrows(IllegalArgumentException.class, () -> WindowCursor.decodeRedisLongLexical(invalidUtf8, "x"));
    }

    private static Map<String,String> fields(String token) {
        return new LinkedHashMap<>(LengthPrefixedPayloadCodec.decode(
                new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)));
    }
    private static String wire(Map<String,String> fields) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                LengthPrefixedPayloadCodec.encode(fields).getBytes(StandardCharsets.UTF_8));
    }
}

package com.reactor.cachedb.core.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LengthPrefixedPayloadCodecTest {
    @Test void preservesLegacyBytesIncludingNullAndUtf16Lengths() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", "42");
        values.put("name", "\u0130\u011fne\ud83d\ude80");
        values.put("empty", "");
        values.put("missing", null);
        String expected = "2:id2:424:name6:\u0130\u011fne\ud83d\ude805:empty0:7:missing-1:";
        assertEquals(expected, LengthPrefixedPayloadCodec.encode(values));
        assertEquals(values, LengthPrefixedPayloadCodec.decode(expected));
        StringBuilder direct = new StringBuilder();
        values.forEach((name, value) -> LengthPrefixedPayloadCodec.appendField(
                direct, LengthPrefixedPayloadCodec.fieldPrefix(name), value));
        assertEquals(expected, direct.toString());
    }

    @Test void rejectsMalformedLengthsWithoutOverflowOrTruncation() {
        for (String malformed : new String[]{"x:", "-2:", "1:a", "1:a-2:", "1:a2147483647:x", "1:a99999999999999999:x", "1:a0", ":"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> LengthPrefixedPayloadCodec.decode(malformed), malformed);
        }
    }

    @Test void boundsFieldsAndPayloadBeforeMaterializingValues() {
        assertThrows(IllegalArgumentException.class, () -> LengthPrefixedPayloadCodec.decode("1:a0:".repeat(LengthPrefixedPayloadCodec.MAX_FIELDS + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> LengthPrefixedPayloadCodec.cursor("x".repeat(LengthPrefixedPayloadCodec.MAX_PAYLOAD_CHARS + 1)));
        assertEquals(1, LengthPrefixedPayloadCodec.decode("1:a0:".repeat(LengthPrefixedPayloadCodec.MAX_FIELDS)).size());
    }

    @Test void acceptsTheFullWidePayloadAndRefusesOneExtraField() {
        assertEquals(4096, LengthPrefixedPayloadCodec.MAX_FIELDS);
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < LengthPrefixedPayloadCodec.MAX_FIELDS; i++) fields.put("c" + i, i % 2 == 0 ? "value" : null);
        assertEquals(fields, LengthPrefixedPayloadCodec.decode(LengthPrefixedPayloadCodec.encode(fields)));
        fields.put("overflow", "value");
        assertThrows(IllegalArgumentException.class, () -> LengthPrefixedPayloadCodec.encode(fields));
    }

    @Test void duplicateAndNullKeysRetainLegacyMapSemantics() {
        assertEquals("2", LengthPrefixedPayloadCodec.decode("1:a1:11:a1:2").get("a"));
        var values = new LinkedHashMap<String, String>();
        values.put(null, null);
        assertEquals(values, LengthPrefixedPayloadCodec.decode(LengthPrefixedPayloadCodec.encode(values)));
        var cursor = LengthPrefixedPayloadCodec.cursor("");
        assertFalse(cursor.next());
        assertThrows(IllegalStateException.class, cursor::value);
    }
}

package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PatchColumnValuesTest {
    @Test void nullNeverCollidesWithALiteralStringOrUnicode() {
        assertNull(PatchColumnValues.decode(PatchColumnValues.encode(null)));
        for (String value : new String[]{"", "__NULL__", "~P:N", "~P:V", "İğne çelik", "a\u0000b"})
            assertEquals(value, PatchColumnValues.decode(PatchColumnValues.encode(value)));
    }
}

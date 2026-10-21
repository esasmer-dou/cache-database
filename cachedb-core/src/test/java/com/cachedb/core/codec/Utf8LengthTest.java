package com.reactor.cachedb.core.codec;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

class Utf8LengthTest {
    @Test
    void matchesJdkForEveryUtf16CodeUnitAndSupplementaryBoundaries() {
        for (int c = Character.MIN_VALUE; c <= Character.MAX_VALUE; c++) {
            String text = String.valueOf((char) c);
            assertEquals(text.getBytes(StandardCharsets.UTF_8).length, Utf8Length.of(text));
        }
        for (String text :
                new String[] {
                    "",
                    "\u0130stanbul",
                    "\ud800\udc00",
                    "\udbff\udfff",
                    "\ud800\ud800\udc00\udc00",
                    "\u0000\u007f\u0080\u07ff\u0800\uffff"
                }) {
            assertEquals(text.getBytes(StandardCharsets.UTF_8).length, Utf8Length.of(text));
        }
        assertThrows(NullPointerException.class, () -> Utf8Length.of(null));
    }

    @Test
    void matchesJdkForSeededMixedStringsAndLargePayloads() {
        var random = new Random(140_021);
        for (int run = 0; run < 1000; run++) {
            char[] chars = new char[random.nextInt(256)];
            for (int i = 0; i < chars.length; i++) chars[i] = (char) random.nextInt(65536);
            String text = new String(chars);
            assertEquals(text.getBytes(StandardCharsets.UTF_8).length, Utf8Length.of(text));
        }
        String text = "\u0130abc\ud83d\ude80\ud800".repeat(100_000);
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length, Utf8Length.of(text));
    }
}

package com.reactor.cachedb.json;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.stream.JsonWriter;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.math.*;
import java.util.Random;

class JsonTokenWriterTest {
    private static final JsonCodecLimits LIMITS = JsonCodecLimits.defaults();

    @Test
    void stringHelperRetainsBothWriterAndCallerLimitsAndRejectsMalformedUtf16() throws Exception {
        for (boolean shared : new boolean[] {true, false}) {
            var target = new StringWriter();
            var writer = new JsonTokenWriter(target, LIMITS);
            JsonStrings.write(
                    writer, "\u0130\ud83d\ude80", shared ? LIMITS : JsonCodecLimits.defaults());
            writer.close();
            assertEquals("\"\u0130\ud83d\ude80\"", target.toString());
        }
        var smaller = new JsonCodecLimits(3, 10, 20, 2);
        var target = new StringWriter();
        var ownLimit = new JsonTokenWriter(target, smaller);
        assertThrows(IOException.class, () -> JsonStrings.write(ownLimit, "abc", LIMITS));
        assertEquals("", target.toString());
        var callerLimit = new JsonTokenWriter(target, LIMITS);
        assertThrows(IOException.class, () -> JsonStrings.write(callerLimit, "abc", smaller));
        assertThrows(IOException.class, () -> JsonStrings.write(callerLimit, "\ud800", LIMITS));
        assertThrows(
                IOException.class,
                () -> JsonStrings.write((JsonTokenWriter) null, "\ud800", LIMITS));
        assertThrows(
                NullPointerException.class,
                () -> JsonStrings.write((JsonTokenWriter) null, "valid", LIMITS));
        assertEquals("", target.toString());
        JsonStrings.write(callerLimit, null, null);
        callerLimit.close();
        assertEquals("null", target.toString());
    }

    @Test
    void escapesEveryValidBmpCharacterAndSupplementaryPairsLikeGson() throws Exception {
        var text = new StringBuilder();
        for (int c = 0; c <= Character.MAX_VALUE; c++) {
            if (c < Character.MIN_SURROGATE || c > Character.MAX_SURROGATE) text.append((char) c);
        }
        text.append("\ud800\udc00\udbff\udfff");
        var expected = new StringWriter();
        var gson = new JsonWriter(expected);
        gson.value(text.toString());
        gson.close();
        var actual = new StringWriter();
        var direct = new JsonTokenWriter(actual, LIMITS);
        direct.value(text.toString());
        direct.close();
        assertEquals(expected.toString(), actual.toString());
        assertEquals(
                text.toString(), new ObjectMapper().readValue(actual.toString(), String.class));
    }

    @Test
    void preservesNumericRepresentationIncludingSignedZero() throws Exception {
        var random = new Random(18293);
        var mapper = new ObjectMapper();
        for (int i = 0; i < 500; i++) {
            Number[] values = {
                random.nextLong(),
                random.nextDouble(),
                random.nextFloat(),
                new BigInteger(250, random),
                BigDecimal.valueOf(random.nextLong(), random.nextInt(100))
            };
            for (Number value : values) {
                var target = new StringWriter();
                var direct = new JsonTokenWriter(target, LIMITS);
                direct.value(value);
                direct.close();
                assertEquals(mapper.readTree(value.toString()), mapper.readTree(target.toString()));
                assertEquals(value.toString(), target.toString());
            }
        }
    }

    @Test
    void rejectsInvalidNumbersBeforeWritingThem() throws Exception {
        for (String token :
                new String[] {
                    "",
                    "-",
                    "01",
                    "+1",
                    "1.",
                    ".1",
                    "1e",
                    "1e+",
                    "1e99999",
                    "NaN",
                    "Infinity",
                    "1 2",
                    "1]"
                }) {
            var target = new StringWriter();
            var direct = new JsonTokenWriter(target, LIMITS);
            Number malicious =
                    new Number() {
                        public int intValue() {
                            return 0;
                        }

                        public long longValue() {
                            return 0;
                        }

                        public float floatValue() {
                            return 0;
                        }

                        public double doubleValue() {
                            return 0;
                        }

                        public String toString() {
                            return token;
                        }
                    };
            assertThrows(IOException.class, () -> direct.value(malicious), token);
            assertEquals("", target.toString());
        }
        assertThrows(
                IOException.class,
                () -> new JsonTokenWriter(new StringWriter(), LIMITS).value(Double.NaN));
        assertThrows(
                IOException.class,
                () ->
                        new JsonTokenWriter(new StringWriter(), LIMITS)
                                .value(Float.POSITIVE_INFINITY));
    }

    @Test
    void enforcesScopeDepthAndUtf16BeforeOutput() throws Exception {
        var target = new StringWriter();
        var direct = new JsonTokenWriter(target, new JsonCodecLimits(2, 10, 20, 100));
        assertThrows(IOException.class, () -> direct.name("outside"));
        direct.beginArray();
        direct.beginObject();
        direct.name("a");
        assertThrows(IOException.class, direct::beginArray);
        assertThrows(IOException.class, direct::endObject);
        assertThrows(IOException.class, () -> direct.value("\ud800"));
        direct.value(1L);
        direct.endObject();
        direct.endArray();
        assertThrows(IOException.class, () -> direct.value(2L));
        direct.close();
        assertEquals("[{\"a\":1}]", target.toString());
        assertThrows(IllegalStateException.class, direct::flush);
        assertThrows(
                IOException.class, () -> new JsonTokenWriter(new StringWriter(), LIMITS).close());
    }

    @Test
    void emitsAllScopesAndNeverClosesCallerWriter() throws Exception {
        class Target extends StringWriter {
            boolean closed;

            public void close() {
                closed = true;
            }
        }
        var target = new Target();
        var direct = new JsonTokenWriter(target, LIMITS);
        direct.beginObject();
        direct.name("values");
        direct.beginArray();
        direct.nullValue();
        direct.value(true);
        direct.value(false);
        direct.value((Boolean) null);
        direct.value(-0.0d);
        direct.value(-0.0f);
        direct.beginObject();
        direct.endObject();
        direct.beginArray();
        direct.endArray();
        direct.endArray();
        direct.name("empty");
        direct.value("");
        direct.endObject();
        direct.close();
        direct.close();
        assertFalse(target.closed);
        assertEquals(
                "{\"values\":[null,true,false,null,-0.0,-0.0,{},[]],\"empty\":\"\"}",
                target.toString());
    }
}

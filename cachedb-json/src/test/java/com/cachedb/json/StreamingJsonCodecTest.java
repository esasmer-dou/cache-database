package com.reactor.cachedb.json;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.stream.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

class StreamingJsonCodecTest {
    @Test
    void emptyArraysKeepStrictValidationAndDoNotInvokeValueDecoders() throws Exception {
        var codec = emptyCodec(JsonCodecLimits.defaults());
        assertEquals(List.of(), codec.decodeArray("[]"));
        assertEquals(List.of(), codec.decodeArray("[ \n ]"));
        assertThrows(UnsupportedOperationException.class, () -> codec.decodeArray("[]").clear());
        assertThrows(IOException.class, () -> codec.decodeArray("[] []"));
        assertThrows(IOException.class, () -> codec.decodeArray("null"));
        assertThrows(IOException.class, () -> codec.decodeArray("["));
        var bounded = emptyCodec(new JsonCodecLimits(1, 1, 1, 2));
        assertEquals(List.of(), bounded.decodeArray("[]"));
        assertThrows(IOException.class, () -> bounded.decodeArray("[ ]"));
    }

    @Test
    void emptyCollectionEncodingKeepsWriterOwnershipMultipleRootsAndFailureHandling()
            throws Exception {
        for (StreamingJsonCodec<?> codec :
                List.of(emptyCodec(JsonCodecLimits.defaults()), new ItemCodec())) {
            class Target extends ByteArrayOutputStream {
                public void flush() {
                    fail("Must not flush caller stream");
                }

                public void close() {
                    fail("Must not close caller stream");
                }
            }
            var target = new Target();
            try (var encoder =
                    codec.openEncoder(target, () -> fail("Empty arrays visit no values"))) {
                encoder.writeArray(List.of());
                encoder.writeArray(Set.of());
            }
            assertEquals("[][]", target.toString(StandardCharsets.UTF_8));
        }
        var codec = emptyCodec(JsonCodecLimits.defaults());
        var target = new ByteArrayOutputStream();
        Iterable<Object> emptyIterable = () -> Collections.emptyIterator();
        try (var encoder = codec.openEncoder(target, () -> fail("No values"))) {
            encoder.writeArray(emptyIterable);
        }
        assertEquals("[]", target.toString(StandardCharsets.UTF_8));
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        var broken =
                new OutputStream() {
                    public void write(int value) throws IOException {
                        writes.incrementAndGet();
                        throw new IOException("broken stream");
                    }
                };
        var encoder = codec.openEncoder(broken, () -> {});
        assertThrows(IOException.class, () -> encoder.writeArray(List.of()));
        encoder.close();
        assertEquals(1, writes.get());
    }

    private StreamingJsonCodec<Object> emptyCodec(JsonCodecLimits limits) {
        return new StreamingJsonCodec<>(limits) {
            public Class<Object> valueType() {
                return Object.class;
            }

            public Object readValue(JsonReader reader, JsonCodecLimits bounds) {
                throw new AssertionError("No values to decode");
            }

            public void writeValue(JsonWriter writer, Object value, JsonCodecLimits bounds) {
                throw new AssertionError("No values to encode");
            }
        };
    }

    public record Item(
            long id,
            String text,
            BigDecimal amount,
            List<Long> links,
            Map<String, String> attributes,
            boolean active,
            Double ratio) {}

    static class ItemCodec extends StreamingJsonCodec<Item> implements GeneratedJsonWriter<Item> {
        ItemCodec() {}

        ItemCodec(JsonCodecLimits limits) {
            super(limits);
        }

        public Class<Item> valueType() {
            return Item.class;
        }

        public Item readValue(JsonReader reader, JsonCodecLimits limits) throws IOException {
            if (JsonValues.isNull(reader)) return null;
            long id = 0;
            String text = null;
            BigDecimal amount = null;
            List<Long> links = null;
            Map<String, String> attributes = null;
            boolean active = false;
            Double ratio = null;
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "id" -> {
                        Long value = JsonValues.longValue(reader, limits);
                        id = value == null ? 0 : value;
                    }
                    case "text" -> text = JsonValues.string(reader, limits);
                    case "amount" -> amount = JsonValues.decimal(reader, limits);
                    case "links" -> links = JsonValues.list(reader, limits, JsonValues::longValue);
                    case "attributes" ->
                            attributes = JsonValues.map(reader, limits, JsonValues::string);
                    case "active" -> {
                        Boolean value = JsonValues.bool(reader, limits);
                        active = value != null && value;
                    }
                    case "ratio" -> ratio = JsonValues.doubleValue(reader, limits);
                    default -> throw new IOException("Unknown field");
                }
            }
            reader.endObject();
            return new Item(id, text, amount, links, attributes, active, ratio);
        }

        public void writeValue(JsonWriter writer, Item value, JsonCodecLimits limits)
                throws IOException {
            if (value == null) {
                writer.nullValue();
                return;
            }
            writer.beginObject();
            writer.name("id");
            JsonValues.number(writer, value.id(), limits);
            writer.name("text");
            JsonValues.string(writer, value.text(), limits);
            writer.name("amount");
            JsonValues.number(writer, value.amount(), limits);
            writer.name("links");
            JsonValues.list(writer, value.links(), limits, JsonValues::number);
            writer.name("attributes");
            JsonValues.map(writer, value.attributes(), limits, JsonValues::string);
            writer.name("active");
            JsonValues.bool(writer, value.active(), limits);
            writer.name("ratio");
            JsonValues.number(writer, value.ratio(), limits);
            writer.endObject();
        }

        public void writeValue(JsonTokenWriter writer, Item value, JsonCodecLimits limits)
                throws IOException {
            if (value == null) {
                writer.nullValue();
                return;
            }
            writer.beginObject();
            writer.name("id");
            JsonValues.number(writer, value.id(), limits);
            writer.name("text");
            JsonValues.string(writer, value.text(), limits);
            writer.name("amount");
            JsonValues.number(writer, value.amount(), limits);
            writer.name("links");
            JsonValues.list(writer, value.links(), limits, JsonValues::number);
            writer.name("attributes");
            JsonValues.map(writer, value.attributes(), limits, JsonValues::string);
            writer.name("active");
            JsonValues.bool(writer, value.active(), limits);
            writer.name("ratio");
            JsonValues.number(writer, value.ratio(), limits);
            writer.endObject();
        }
    }

    private final ItemCodec codec = new ItemCodec();

    @Test
    void preservesCustomGsonWriterFallback() throws Exception {
        StreamingJsonCodec<Item> legacy =
                new StreamingJsonCodec<>() {
                    public Class<Item> valueType() {
                        return Item.class;
                    }

                    public Item readValue(JsonReader reader, JsonCodecLimits limits)
                            throws IOException {
                        return codec.readValue(reader, limits);
                    }

                    public void writeValue(JsonWriter writer, Item value, JsonCodecLimits limits)
                            throws IOException {
                        codec.writeValue(writer, value, limits);
                    }
                };
        var controls = new StringBuilder();
        for (char c = 0; c < 32; c++) controls.append(c);
        var value =
                new Item(
                        Long.MIN_VALUE,
                        controls + "\u2028\u2029\ud83d\ude80\\\"",
                        new BigDecimal("-1.25E-100"),
                        Arrays.asList(null, Long.MAX_VALUE),
                        Collections.singletonMap("nullable", null),
                        true,
                        -0.0);
        assertEquals(encode(legacy, List.of(value)), encode(codec, List.of(value)));
        assertEquals(List.of(value), legacy.decodeArray(encode(codec, List.of(value))));
    }

    private static String encode(StreamingJsonCodec<Item> codec, List<Item> values)
            throws IOException {
        var bytes = new ByteArrayOutputStream();
        codec.encodeArray(values, bytes);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void matchesJacksonForUnicodeNullsNumbersAndNestedCollections() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Random random = new Random(72831);
        String[] pieces = {
            "I",
            "\u0131\u0130\u015f\u011f\u00fc\u00f6\u00e7",
            "\u6f22\u5b57",
            "\ud83d\ude80",
            "\n\t\u0000",
            "\\\"/",
            "\u2028\u2029"
        };
        for (int i = 0; i < 500; i++) {
            var attributes = new LinkedHashMap<String, String>();
            attributes.put("a", pieces[random.nextInt(pieces.length)]);
            attributes.put("nullable", null);
            Item value =
                    new Item(
                            random.nextLong(),
                            pieces[random.nextInt(pieces.length)],
                            BigDecimal.valueOf(random.nextInt(100000), random.nextInt(5)),
                            Arrays.asList(null, random.nextLong()),
                            attributes,
                            random.nextBoolean(),
                            random.nextDouble());
            String legacy = mapper.writeValueAsString(List.of(value));
            String fast = encode(codec, List.of(value));
            assertEquals(List.of(value), codec.decodeArray(legacy));
            assertEquals(List.of(value), mapper.readerForListOf(Item.class).readValue(fast));
            assertEquals(mapper.readTree(legacy), mapper.readTree(fast));
        }
        assertEquals(
                List.of(new Item(0, null, null, null, null, false, null)),
                codec.decodeArray("[{}]"));
        assertEquals(
                List.of(new Item(0, null, null, null, null, false, null)),
                codec.decodeArray("[{\"id\":null,\"active\":null}]"));
    }

    @Test
    void rejectsMalformedOrAmbiguousPayloadsWithoutFallback() {
        for (String payload :
                List.of(
                        "",
                        "null",
                        "{}",
                        "[null]",
                        "[] []",
                        "[{'text':'x'}]",
                        "[{text:1}]",
                        "[{\"id\":1,}]",
                        "[{\"unknown\":1}]",
                        "[{\"id\":9223372036854775808}]",
                        "[{\"id\":1.2}]",
                        "[{\"ratio\":1e999}]",
                        "[{\"id\":1e999999999}]",
                        "[{\"amount\":1e-999999999}]",
                        "[{\"ratio\":NaN}]",
                        "[{\"text\":\"\\uD800\"}]",
                        "[{\"text\":42}]"))
            assertThrows(IOException.class, () -> codec.decodeArray(payload), payload);
    }

    @Test
    void duplicateNamesFollowLastValueAndMissingValuesHaveRecordDefaults() throws Exception {
        assertEquals(2L, codec.decodeArray("[{\"id\":1,\"id\":2}]").get(0).id());
        assertEquals(List.of(), codec.decodeArray("[]"));
    }

    @Test
    void enforcesReadAndWriteDepthDocumentElementAndNumberLimits() throws Exception {
        Item value = new Item(1234L, "text", null, List.of(1L, 2L), null, true, null);
        String json = encode(codec, List.of(value));
        for (JsonCodecLimits limits :
                List.of(
                        new JsonCodecLimits(2, 100, 100, 10000),
                        new JsonCodecLimits(10, 1, 100, 10000),
                        new JsonCodecLimits(10, 100, 3, 10000),
                        new JsonCodecLimits(10, 100, 100, 20))) {
            ItemCodec limited = new ItemCodec(limits);
            assertThrows(IOException.class, () -> limited.decodeArray(json));
            assertThrows(IOException.class, () -> encode(limited, List.of(value)));
        }
        assertThrows(
                IOException.class, () -> codec.decodeArray("[{\"id\":" + "1".repeat(1025) + "}]"));
        assertThrows(
                IOException.class,
                () -> encode(codec, List.of(new Item(1, "\ud800", null, null, null, false, null))));
        assertThrows(
                IOException.class,
                () ->
                        encode(
                                codec,
                                List.of(new Item(1, null, null, null, null, false, Double.NaN))));
        assertThrows(
                IOException.class,
                () ->
                        encode(
                                codec,
                                List.of(
                                        new Item(
                                                1,
                                                null,
                                                new BigDecimal("1e100000"),
                                                null,
                                                null,
                                                false,
                                                null))));
    }

    @Test
    void reusableEncoderKeepsRootOffsetsWithoutClosingOrFlushingTarget() throws Exception {
        class Target extends ByteArrayOutputStream {
            int flushes, closes;

            public void flush() {
                flushes++;
            }

            public void close() {
                closes++;
            }
        }
        Target target = new Target();
        Item item = new Item(1, "\u0130\u011f\ud83d\ude80", null, null, null, true, 1.25);
        try (var encoder = codec.openEncoder(target, () -> {})) {
            encoder.writeArray(List.of(item));
            int firstEnd = target.size();
            encoder.writeArray(List.of());
            assertEquals(
                    List.of(item),
                    codec.decodeArray(
                            new String(target.toByteArray(), 0, firstEnd, StandardCharsets.UTF_8)));
            assertEquals(
                    "[]",
                    new String(
                            target.toByteArray(),
                            firstEnd,
                            target.size() - firstEnd,
                            StandardCharsets.UTF_8));
        }
        assertEquals(0, target.closes);
        assertEquals(0, target.flushes);
    }

    @Test
    void failedOutputIsNotRetriedOnCloseAndCannotBeReused() throws Exception {
        for (List<Item> values :
                List.of(
                        List.<Item>of(),
                        List.of(new Item(1, "ascii", null, null, null, true, null)),
                        List.of(
                                new Item(
                                        1,
                                        "\u0130\ud83d\ude80".repeat(5000),
                                        null,
                                        null,
                                        null,
                                        true,
                                        null)))) {
            IOException failure = new IOException("write failed");
            int[] writes = {0};
            OutputStream target =
                    new OutputStream() {
                        public void write(int value) throws IOException {
                            writes[0]++;
                            throw failure;
                        }

                        public void write(byte[] data, int offset, int length) throws IOException {
                            writes[0]++;
                            throw failure;
                        }
                    };
            var encoder = codec.openEncoder(target, () -> {});
            assertSame(failure, assertThrows(IOException.class, () -> encoder.writeArray(values)));
            encoder.close();
            assertEquals(1, writes[0]);
            assertThrows(IllegalStateException.class, () -> encoder.writeArray(List.of()));
        }
    }

    @Test
    void sharedCodecHasNoCrossThreadState() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int id = i;
                tasks.add(
                        () -> {
                            Item value = new Item(id, "value-" + id, null, null, null, true, null);
                            return codec.decodeArray(encode(codec, List.of(value)))
                                    .equals(List.of(value));
                        });
            }
            for (var result : executor.invokeAll(tasks, 5, TimeUnit.SECONDS))
                assertTrue(result.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oversizedNumberIsRejectedBeforeAllocatingItsDecimalText() throws Exception {
        var value =
                new BigDecimal(java.math.BigInteger.ONE.shiftLeft(100_000)) {
                    @Override
                    public String toString() {
                        throw new AssertionError("Decimal text must not be allocated");
                    }
                };
        assertThrows(
                IOException.class,
                () -> encode(codec, List.of(new Item(1, null, value, null, null, false, null))));
    }

    @Test
    void primitiveFloatingPointWritersRespectTheReadersExponentBudget() throws Exception {
        var output = new StringWriter();
        try (var writer = new JsonWriter(output)) {
            writer.beginArray();
            assertThrows(
                    IOException.class,
                    () -> JsonValues.number(writer, 1e200, new JsonCodecLimits(4, 10, 128, 1024)));
            assertThrows(
                    IOException.class,
                    () -> JsonValues.number(writer, 1e30F, new JsonCodecLimits(4, 10, 8, 1024)));
            JsonValues.number(writer, 1e10, new JsonCodecLimits(4, 10, 128, 1024));
            writer.endArray();
        }
        try (var reader = new JsonReader(new StringReader(output.toString()))) {
            reader.beginArray();
            assertEquals(
                    1e10, JsonValues.doubleValue(reader, new JsonCodecLimits(4, 10, 128, 1024)));
            reader.endArray();
        }
    }
}

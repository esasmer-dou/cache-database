package com.reactor.cachedb.tools.resources;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded token streaming; no databinding, reflection or guessed JSON extraction. */
final class BoundedJson {
    static final int MAX_INPUT_BYTES = 1_048_576;
    static final int MAX_OUTPUT_BYTES = 65_536;
    private static final int MAX_NODES = 20_000;
    private static final int MAX_DEPTH = 32;
    private BoundedJson() {}

    static Object read(InputStream source) throws IOException {
        byte[] bytes = source.readNBytes(MAX_INPUT_BYTES + 1);
        if (bytes.length > MAX_INPUT_BYTES) throw invalid();
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        return read(text);
    }

    static Object read(String text) throws IOException {
        if (text.length() > MAX_INPUT_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES)
            throw invalid();
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            reader.setNestingLimit(MAX_DEPTH);
            Object result = value(reader, new int[1], 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            return result;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static Object value(JsonReader reader, int[] nodes, int depth) throws IOException {
        if (++nodes[0] > MAX_NODES || depth > MAX_DEPTH) throw invalid();
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var values = new LinkedHashMap<String, Object>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = token(reader.nextName());
                    if (values.containsKey(name)) throw invalid();
                    values.put(name, value(reader, nodes, depth + 1));
                }
                reader.endObject();
                yield values;
            }
            case BEGIN_ARRAY -> {
                var values = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) values.add(value(reader, nodes, depth + 1));
                reader.endArray();
                yield values;
            }
            case STRING -> token(reader.nextString());
            case NUMBER -> {
                String number = reader.nextString();
                if (number.length() > 128) throw invalid();
                BigDecimal decimal = new BigDecimal(number);
                if (Math.abs((long) decimal.scale()) > 4096) throw invalid();
                yield decimal.stripTrailingZeros();
            }
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> { reader.nextNull(); yield null; }
            default -> throw invalid();
        };
    }

    private static String token(String text) throws IOException {
        if (text.length() > 65_536) throw invalid();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
        return text;
    }

    static String write(Object value) throws IOException {
        var output = new BoundedWriter();
        try (var writer = new JsonWriter(output)) { write(writer, value, 0); }
        String text = output.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) throw invalid();
        return text;
    }

    private static final class BoundedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        @Override public void write(char[] buffer, int offset, int count) throws IOException {
            if (count > MAX_OUTPUT_BYTES - text.length()) throw invalid();
            text.append(buffer, offset, count);
        }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() { return text.toString(); }
    }

    private static void write(JsonWriter writer, Object value, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw invalid();
        if (value == null) writer.nullValue();
        else if (value instanceof String text) writer.value(text);
        else if (value instanceof Boolean flag) writer.value(flag);
        else if (value instanceof Number number) writer.value(number);
        else if (value instanceof Map<?, ?> fields) {
            writer.beginObject();
            for (var entry : fields.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalid();
                writer.name(key); write(writer, entry.getValue(), depth + 1);
            }
            writer.endObject();
        } else if (value instanceof List<?> values) {
            writer.beginArray();
            for (var item : values) write(writer, item, depth + 1);
            writer.endArray();
        } else throw invalid();
    }

    private static IOException invalid() { return new IOException("Invalid or oversized JSON document"); }
}

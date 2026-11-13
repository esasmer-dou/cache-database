package com.reactor.cachedb.spring.boot.cache;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.reactor.cachedb.core.codec.CacheValueCodec;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

/** Existing Jackson token API only: no databinding, default typing, Class.forName or POJO discovery. */
public final class CacheDbJsonCacheValueCodec implements CacheValueCodec<Object> {
    private final int maxChars;
    private final JsonFactory factory;

    public CacheDbJsonCacheValueCodec(int maxValueBytes) {
        if (maxValueBytes < 1 || maxValueBytes > CacheDbCacheConfiguration.MAX_VALUE_BYTES)
            throw new IllegalArgumentException("Invalid JSON budget");
        maxChars = maxValueBytes;
        factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(4).maxStringLength(maxChars).maxNumberLength(2048).build()).build();
    }

    @Override public String encodeValue(Object value) throws IOException {
        String type;
        String text;
        if (value instanceof String v) { type = "string"; text = v; }
        else if (value instanceof Character v) { type = "char"; text = v.toString(); }
        else if (value instanceof Boolean v) { type = "boolean"; text = v.toString(); }
        else if (value instanceof Byte v) { type = "byte"; text = v.toString(); }
        else if (value instanceof Short v) { type = "short"; text = v.toString(); }
        else if (value instanceof Integer v) { type = "int"; text = v.toString(); }
        else if (value instanceof Long v) { type = "long"; text = v.toString(); }
        else if (value instanceof Float v && Float.isFinite(v)) { type = "float"; text = v.toString(); }
        else if (value instanceof Double v && Double.isFinite(v)) { type = "double"; text = v.toString(); }
        else if (value instanceof UUID v) { type = "uuid"; text = v.toString(); }
        else if (value instanceof BigInteger v && v.getClass() == BigInteger.class && v.bitLength() <= 4096) {
            type = "bigint"; text = v.toString();
        } else if (value instanceof BigDecimal v && v.getClass() == BigDecimal.class
                && v.unscaledValue().bitLength() <= 4096) { type = "decimal"; text = v.toString(); }
        else throw new IOException("Default JSON cache codec accepts finite scalar values only; register CacheDbCacheCodec for this cache");
        if (text.length() > maxChars) throw new IOException("JSON cache value exceeds character budget");
        CacheDbCacheKeys.unicode(text);
        StringWriter result = new StringWriter(Math.min(1024, maxChars));
        try (JsonGenerator json = factory.createGenerator(new LimitedWriter(result, maxChars))) {
            json.writeStartArray();
            json.writeString(type);
            json.writeString(text);
            json.writeEndArray();
        }
        return result.toString();
    }

    @Override public Object decodeValue(String encoded) throws IOException {
        if (encoded.length() > maxChars) throw new IOException("JSON cache value exceeds character budget");
        try (JsonParser parser = factory.createParser(encoded)) {
            expect(parser, JsonToken.START_ARRAY);
            expect(parser, JsonToken.VALUE_STRING);
            String type = parser.getText();
            if (type.length() > 16) throw new IOException("Invalid JSON cache value tag length");
            expect(parser, JsonToken.VALUE_STRING);
            String text = parser.getText();
            CacheDbCacheKeys.unicode(text);
            if (!type.equals("string") && text.length() > 2048)
                throw new IOException("Scalar cache value exceeds numeric/identity budget");
            expect(parser, JsonToken.END_ARRAY);
            if (parser.nextToken() != null) throw new IOException("Trailing JSON cache content");
            return switch (type) {
                case "string" -> text;
                case "char" -> {
                    if (text.length() != 1) throw new IOException("Invalid character cache value");
                    yield text.charAt(0);
                }
                case "boolean" -> {
                    if (!text.equals("true") && !text.equals("false")) throw new IOException("Invalid boolean cache value");
                    yield Boolean.valueOf(text);
                }
                case "byte" -> Byte.valueOf(text);
                case "short" -> Short.valueOf(text);
                case "int" -> Integer.valueOf(text);
                case "long" -> Long.valueOf(text);
                case "float" -> {
                    Float v = Float.valueOf(text);
                    if (!Float.isFinite(v)) throw new IOException("Non-finite cache value");
                    yield v;
                }
                case "double" -> {
                    Double v = Double.valueOf(text);
                    if (!Double.isFinite(v)) throw new IOException("Non-finite cache value");
                    yield v;
                }
                case "bigint" -> {
                    BigInteger value = new BigInteger(numeric(text));
                    if (value.bitLength() > 4096) throw new IOException("Numeric cache value exceeds 4096 bits");
                    yield value;
                }
                case "decimal" -> {
                    BigDecimal value = new BigDecimal(numeric(text));
                    if (value.unscaledValue().bitLength() > 4096) throw new IOException("Numeric cache value exceeds 4096 bits");
                    yield value;
                }
                case "uuid" -> UUID.fromString(text);
                default -> throw new IOException("Unknown JSON cache value tag");
            };
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid scalar JSON cache value", invalid); }
    }

    private static String numeric(String value) throws IOException {
        if (value.length() > 2048) throw new IOException("Numeric cache value exceeds budget");
        return value;
    }
    private static void expect(JsonParser parser, JsonToken token) throws IOException {
        if (parser.nextToken() != token) throw new IOException("Invalid JSON cache value shape");
    }
    private static final class LimitedWriter extends Writer {
        private final StringWriter delegate;
        private final int limit;
        private int count;
        LimitedWriter(StringWriter delegate, int limit) { this.delegate = delegate; this.limit = limit; }
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            if (length > limit - count) throw new IOException("JSON cache value exceeds character budget");
            count += length;
            delegate.write(chars, offset, length);
        }
        @Override public void flush() {}
        @Override public void close() {}
    }
}

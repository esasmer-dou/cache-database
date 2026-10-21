package com.reactor.cachedb.json;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.math.*;
import java.util.*;
import java.util.function.Function;

/** Helpers called directly by generated source; no reflective fallback for unknown types. */
public final class JsonValues {
    private JsonValues() {}

    @FunctionalInterface
    public interface Reader<T> {
        T read(JsonReader reader, JsonCodecLimits limits) throws IOException;
    }

    @FunctionalInterface
    public interface Writer<T> {
        void write(JsonWriter writer, T value, JsonCodecLimits limits) throws IOException;
    }

    public static boolean isNull(JsonReader reader) throws IOException {
        return JsonStrings.isNull(reader);
    }

    public static String string(JsonReader reader, JsonCodecLimits limits) throws IOException {
        return JsonStrings.read(reader, limits);
    }

    public static void string(JsonWriter writer, String value, JsonCodecLimits limits)
            throws IOException {
        JsonStrings.write(writer, value, limits);
    }

    private static void validString(String value, JsonCodecLimits limits) throws IOException {
        JsonStrings.validate(value, limits);
    }

    public static String number(JsonReader reader, JsonCodecLimits limits) throws IOException {
        if (isNull(reader)) return null;
        if (reader.peek() != JsonToken.NUMBER)
            throw new IOException("Expected JSON number at " + reader.getPath());
        String value = reader.nextString();
        if (value.length() > limits.maxNumberDigits())
            throw new IOException("JSON number budget exceeded");
        boundedExponent(value, limits);
        return value;
    }

    private static void boundedExponent(String value, JsonCodecLimits limits) throws IOException {
        int start = Math.max(value.indexOf('e'), value.indexOf('E'));
        if (start < 0) return;
        int magnitude = 0;
        for (int i = start + 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '+' || c == '-') continue;
            magnitude = magnitude * 10 + c - '0';
            if (magnitude > limits.maxNumberDigits())
                throw new IOException("JSON exponent budget exceeded");
        }
    }

    public static BigDecimal decimal(JsonReader reader, JsonCodecLimits limits) throws IOException {
        String value = number(reader, limits);
        return value == null ? null : new BigDecimal(value);
    }

    public static BigInteger bigInteger(JsonReader reader, JsonCodecLimits limits)
            throws IOException {
        String value = number(reader, limits);
        if (value == null) return null;
        // Do not expand an attacker-controlled decimal exponent into an unbounded integer.
        if (value.indexOf('.') >= 0 || value.indexOf('e') >= 0 || value.indexOf('E') >= 0)
            throw new IOException("BigInteger requires an integer JSON token");
        return new BigInteger(value);
    }

    public static Long longValue(JsonReader reader, JsonCodecLimits limits) throws IOException {
        String value = number(reader, limits);
        return value == null
                ? null
                : fractional(value) ? new BigDecimal(value).longValueExact() : Long.valueOf(value);
    }

    public static Integer integer(JsonReader reader, JsonCodecLimits limits) throws IOException {
        String value = number(reader, limits);
        return value == null
                ? null
                : fractional(value)
                        ? new BigDecimal(value).intValueExact()
                        : Integer.valueOf(value);
    }

    public static Short shortValue(JsonReader reader, JsonCodecLimits limits) throws IOException {
        BigDecimal value = decimal(reader, limits);
        return value == null ? null : value.shortValueExact();
    }

    public static Byte byteValue(JsonReader reader, JsonCodecLimits limits) throws IOException {
        BigDecimal value = decimal(reader, limits);
        return value == null ? null : value.byteValueExact();
    }

    public static Double doubleValue(JsonReader reader, JsonCodecLimits limits) throws IOException {
        String token = number(reader, limits);
        if (token == null) return null;
        double value = Double.parseDouble(token);
        if (!Double.isFinite(value)) throw new IOException("Non-finite JSON number");
        return value;
    }

    public static Float floatValue(JsonReader reader, JsonCodecLimits limits) throws IOException {
        String token = number(reader, limits);
        if (token == null) return null;
        float value = Float.parseFloat(token);
        if (!Float.isFinite(value)) throw new IOException("Non-finite JSON number");
        return value;
    }

    public static Boolean bool(JsonReader reader, JsonCodecLimits limits) throws IOException {
        return isNull(reader) ? null : reader.nextBoolean();
    }

    private static boolean fractional(String value) {
        return value.indexOf('.') >= 0 || value.indexOf('e') >= 0 || value.indexOf('E') >= 0;
    }

    public static <T> T mappedString(
            JsonReader reader, JsonCodecLimits limits, Function<String, T> decode)
            throws IOException {
        String value = string(reader, limits);
        return value == null ? null : decode.apply(value);
    }

    public static void bool(JsonWriter writer, Boolean value, JsonCodecLimits limits)
            throws IOException {
        writer.value(value);
    }

    public static void bool(JsonWriter writer, boolean value, JsonCodecLimits limits)
            throws IOException {
        writer.value(value);
    }

    public static void character(JsonWriter writer, Character value, JsonCodecLimits limits)
            throws IOException {
        string(writer, value == null ? null : value.toString(), limits);
    }

    public static Character character(JsonReader reader, JsonCodecLimits limits)
            throws IOException {
        String value = string(reader, limits);
        if (value == null) return null;
        if (value.length() != 1) throw new IOException("Expected one JSON character");
        return value.charAt(0);
    }

    public static void number(JsonWriter writer, Number value, JsonCodecLimits limits)
            throws IOException {
        numberToken(value, limits);
        writer.value(value);
    }

    static String numberToken(Number value, JsonCodecLimits limits) throws IOException {
        if (value != null) {
            // Bound magnitude before decimal conversion can allocate a huge temporary string.
            long bits = 4L * limits.maxNumberDigits();
            if (value instanceof BigInteger integer && integer.bitLength() > bits
                    || value instanceof BigDecimal decimal
                            && (decimal.unscaledValue().bitLength() > bits
                                    || Math.abs((long) decimal.scale())
                                            > 2L * limits.maxNumberDigits())
                    || value instanceof Double d && !Double.isFinite(d)
                    || value instanceof Float f && !Float.isFinite(f))
                throw new IOException("Invalid or oversized JSON number");
            String token = value.toString();
            if (token.length() > limits.maxNumberDigits())
                throw new IOException("JSON number budget exceeded");
            boundedExponent(token, limits);
            return token;
        }
        return null;
    }

    public static void number(JsonWriter writer, long value, JsonCodecLimits limits)
            throws IOException {
        if (limits.maxNumberDigits() < 20
                && Long.toString(value).length() > limits.maxNumberDigits())
            throw new IOException("JSON number budget exceeded");
        writer.value(value);
    }

    public static void number(JsonWriter writer, double value, JsonCodecLimits limits)
            throws IOException {
        validDouble(value, limits);
        writer.value(value);
    }

    private static void validDouble(double value, JsonCodecLimits limits) throws IOException {
        if (!Double.isFinite(value)) throw new IOException("Invalid or oversized JSON number");
        if (limits.maxNumberDigits() < 325) {
            String token = Double.toString(value);
            if (token.length() > limits.maxNumberDigits())
                throw new IOException("JSON number budget exceeded");
            boundedExponent(token, limits);
        }
    }

    public static void number(JsonWriter writer, float value, JsonCodecLimits limits)
            throws IOException {
        validFloat(value, limits);
        writer.value(value);
    }

    private static void validFloat(float value, JsonCodecLimits limits) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("Invalid or oversized JSON number");
        if (limits.maxNumberDigits() < 46) {
            String token = Float.toString(value);
            if (token.length() > limits.maxNumberDigits())
                throw new IOException("JSON number budget exceeded");
            boundedExponent(token, limits);
        }
    }

    public static <T> List<T> list(JsonReader reader, JsonCodecLimits limits, Reader<T> decoder)
            throws IOException {
        if (isNull(reader)) return null;
        reader.beginArray();
        List<T> result = new ArrayList<>();
        while (reader.hasNext()) {
            if (result.size() >= limits.maxArrayElements())
                throw new IOException("JSON array element budget exceeded");
            result.add(decoder.read(reader, limits));
        }
        reader.endArray();
        return result;
    }

    public static <T> void list(
            JsonWriter writer, List<T> values, JsonCodecLimits limits, Writer<T> encoder)
            throws IOException {
        if (values == null) {
            writer.nullValue();
            return;
        }
        if (values.size() > limits.maxArrayElements())
            throw new IOException("JSON array element budget exceeded");
        writer.beginArray();
        for (T value : values) encoder.write(writer, value, limits);
        writer.endArray();
    }

    public static <T> Map<String, T> map(
            JsonReader reader, JsonCodecLimits limits, Reader<T> decoder) throws IOException {
        if (isNull(reader)) return null;
        reader.beginObject();
        Map<String, T> result = new LinkedHashMap<>();
        int count = 0;
        while (reader.hasNext()) {
            if (++count > limits.maxArrayElements())
                throw new IOException("JSON map entry budget exceeded");
            String key = reader.nextName();
            validString(key, limits);
            result.put(key, decoder.read(reader, limits));
        }
        reader.endObject();
        return result;
    }

    public static <T> void map(
            JsonWriter writer, Map<String, T> values, JsonCodecLimits limits, Writer<T> encoder)
            throws IOException {
        if (values == null) {
            writer.nullValue();
            return;
        }
        if (values.size() > limits.maxArrayElements())
            throw new IOException("JSON map entry budget exceeded");
        writer.beginObject();
        for (Map.Entry<String, T> entry : values.entrySet()) {
            validString(Objects.requireNonNull(entry.getKey(), "JSON map key"), limits);
            writer.name(entry.getKey());
            encoder.write(writer, entry.getValue(), limits);
        }
        writer.endObject();
    }

    @FunctionalInterface
    public interface TokenWriter<T> {
        void write(JsonTokenWriter writer, T value, JsonCodecLimits limits) throws IOException;
    }

    public static void string(JsonTokenWriter writer, String value, JsonCodecLimits limits)
            throws IOException {
        JsonStrings.write(writer, value, limits);
    }

    public static void bool(JsonTokenWriter writer, Boolean value, JsonCodecLimits limits)
            throws IOException {
        writer.value(value);
    }

    public static void bool(JsonTokenWriter writer, boolean value, JsonCodecLimits limits)
            throws IOException {
        writer.value(value);
    }

    public static void character(JsonTokenWriter writer, Character value, JsonCodecLimits limits)
            throws IOException {
        string(writer, value == null ? null : value.toString(), limits);
    }

    public static void number(JsonTokenWriter writer, Number value, JsonCodecLimits limits)
            throws IOException {
        writer.value(value, limits);
    }

    public static void number(JsonTokenWriter writer, long value, JsonCodecLimits limits)
            throws IOException {
        if (limits.maxNumberDigits() < 20
                && Long.toString(value).length() > limits.maxNumberDigits())
            throw new IOException("JSON number budget exceeded");
        writer.value(value);
    }

    public static void number(JsonTokenWriter writer, double value, JsonCodecLimits limits)
            throws IOException {
        validDouble(value, limits);
        writer.value(value);
    }

    public static void number(JsonTokenWriter writer, float value, JsonCodecLimits limits)
            throws IOException {
        validFloat(value, limits);
        writer.value(value);
    }

    public static <T> void list(
            JsonTokenWriter writer, List<T> values, JsonCodecLimits limits, TokenWriter<T> encoder)
            throws IOException {
        if (values == null) {
            writer.nullValue();
            return;
        }
        if (values.size() > limits.maxArrayElements())
            throw new IOException("JSON array element budget exceeded");
        writer.beginArray();
        for (T value : values) encoder.write(writer, value, limits);
        writer.endArray();
    }

    public static <T> void map(
            JsonTokenWriter writer,
            Map<String, T> values,
            JsonCodecLimits limits,
            TokenWriter<T> encoder)
            throws IOException {
        if (values == null) {
            writer.nullValue();
            return;
        }
        if (values.size() > limits.maxArrayElements())
            throw new IOException("JSON map entry budget exceeded");
        writer.beginObject();
        for (var entry : values.entrySet()) {
            validString(Objects.requireNonNull(entry.getKey(), "JSON map key"), limits);
            writer.name(entry.getKey());
            encoder.write(writer, entry.getValue(), limits);
        }
        writer.endObject();
    }
}

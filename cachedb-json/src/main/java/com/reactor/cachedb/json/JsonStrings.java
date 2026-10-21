package com.reactor.cachedb.json;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/** Small string/null token path; loading a string-only DTO does not initialize numeric binders. */
public final class JsonStrings {
    private JsonStrings() {}

    public static boolean isNull(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NULL) return false;
        reader.nextNull();
        return true;
    }

    public static String read(JsonReader reader, JsonCodecLimits limits) throws IOException {
        if (isNull(reader)) return null;
        if (reader.peek() != JsonToken.STRING)
            throw new IOException("Expected JSON string at " + reader.getPath());
        String value = reader.nextString();
        validate(value, limits);
        return value;
    }

    public static void write(JsonWriter writer, String value, JsonCodecLimits limits)
            throws IOException {
        if (value != null) validate(value, limits);
        writer.value(value);
    }

    public static void write(JsonTokenWriter writer, String value, JsonCodecLimits limits)
            throws IOException {
        if (value != null && (writer == null || !writer.usesLimits(limits)))
            validate(value, limits);
        writer.value(value);
    }

    static void validate(String value, JsonCodecLimits limits) throws IOException {
        if (value.length() > limits.maxDocumentChars())
            throw new IOException("JSON string budget exceeded");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IOException("Unpaired JSON surrogate");
            } else if (Character.isLowSurrogate(c))
                throw new IOException("Unpaired JSON surrogate");
        }
    }
}

package com.reactor.cachedb.json;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.reactor.cachedb.core.codec.JsonArrayCodec;

import java.io.*;
import java.util.*;

/** Token streaming only: no Gson databinding, reflection, registries or runtime type discovery. */
public abstract class StreamingJsonCodec<V> implements JsonArrayCodec<V> {
    private final JsonCodecLimits limits;

    protected StreamingJsonCodec() {
        this(JsonCodecLimits.defaults());
    }

    protected StreamingJsonCodec(JsonCodecLimits limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    public abstract V readValue(JsonReader reader, JsonCodecLimits limits) throws IOException;

    public abstract void writeValue(JsonWriter writer, V value, JsonCodecLimits limits)
            throws IOException;

    @Override
    public final List<V> decodeArray(String json) throws IOException {
        Objects.requireNonNull(json, "json");
        if (json.length() > limits.maxDocumentChars())
            throw new IOException("JSON document character budget exceeded");
        if (json.equals("[]")) return List.of();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.setNestingLimit(limits.maxDepth());
            reader.beginArray();
            List<V> values = new ArrayList<>();
            while (reader.hasNext()) {
                if (values.size() >= limits.maxArrayElements())
                    throw new IOException("JSON array element budget exceeded");
                values.add(
                        Objects.requireNonNull(readValue(reader, limits), "Null snapshot value"));
            }
            reader.endArray();
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new IOException("Trailing JSON content");
            return List.copyOf(values);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid JSON array for " + valueType().getName(), failure);
        }
    }

    @Override
    public final ArrayEncoder<V> openEncoder(OutputStream target, Runnable checkpoint) {
        return JsonArrayEncoding.open(this, limits, target, checkpoint);
    }
}

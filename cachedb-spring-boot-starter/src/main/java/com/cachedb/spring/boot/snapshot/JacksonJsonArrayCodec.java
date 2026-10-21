package com.reactor.cachedb.spring.boot.snapshot;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.reactor.cachedb.core.codec.JsonArrayCodec;

import java.io.*;
import java.util.*;

/** Legacy mapper semantics are isolated here, not silently translated to a different engine. */
final class JacksonJsonArrayCodec<V> implements JsonArrayCodec<V> {
    private final ObjectMapper mapper;
    private final Class<V> type;
    private volatile ObjectReader reader;

    JacksonJsonArrayCodec(ObjectMapper mapper, Class<V> type) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.type = Objects.requireNonNull(type, "type");
    }

    public Class<V> valueType() {
        return type;
    }

    JacksonJsonArrayCodec<V> prepared() {
        ObjectReader value = reader;
        if (value == null)
            synchronized (this) {
                value = reader;
                if (value == null) reader = value = mapper.readerForListOf(type);
            }
        return this;
    }

    public List<V> decodeArray(String json) throws IOException {
        prepared();
        return Objects.requireNonNull(
                reader.readValue(json), "Snapshot payload must be an array, not null");
    }

    public ArrayEncoder<V> openEncoder(OutputStream target, Runnable checkpoint)
            throws IOException {
        ObjectWriter writer =
                mapper.writerFor(type).without(SerializationFeature.FLUSH_AFTER_WRITE_VALUE);
        JsonGenerator json = mapper.getFactory().createGenerator(target);
        json.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        json.disable(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM);
        json.setRootValueSeparator(null);
        return new ArrayEncoder<>() {
            private int values;

            public void writeArray(Iterable<? extends V> items) throws IOException {
                json.writeStartArray();
                for (V value : items) {
                    if ((values++ & 255) == 0) checkpoint.run();
                    writer.writeValue(json, Objects.requireNonNull(value, "Null snapshot value"));
                }
                json.writeEndArray();
                json.flush();
            }

            public void close() throws IOException {
                json.close();
            }
        };
    }
}

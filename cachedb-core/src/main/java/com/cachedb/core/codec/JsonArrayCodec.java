package com.reactor.cachedb.core.codec;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Explicit JSON-array contract. Encoders drain internal buffers without flushing/closing the
 * target.
 */
public interface JsonArrayCodec<V> {
    Class<V> valueType();

    List<V> decodeArray(String json) throws IOException;

    ArrayEncoder<V> openEncoder(OutputStream target, Runnable checkpoint) throws IOException;

    default void encodeArray(Iterable<? extends V> values, OutputStream target) throws IOException {
        try (ArrayEncoder<V> encoder = openEncoder(target, JsonNoopCheckpoint.INSTANCE)) {
            encoder.writeArray(values);
        }
    }

    interface ArrayEncoder<V> extends AutoCloseable {
        void writeArray(Iterable<? extends V> values) throws IOException;

        @Override
        void close() throws IOException;
    }
}

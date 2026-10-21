package com.reactor.cachedb.json;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonWriter;
import com.reactor.cachedb.core.codec.JsonArrayCodec;

import java.io.*;
import java.util.Collection;
import java.util.Objects;

/** Kept off the decode-only initialization path. One UTF-8 encoder is shared across roots. */
final class JsonArrayEncoding {
    private JsonArrayEncoding() {}

    static <V> JsonArrayCodec.ArrayEncoder<V> open(
            StreamingJsonCodec<V> codec,
            JsonCodecLimits limits,
            OutputStream target,
            Runnable checkpoint) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Writer utf8 = new JsonUtf8Writer(target);
        return new JsonArrayCodec.ArrayEncoder<>() {
            private boolean closed, failed;
            private long chars;
            private int visitedValues;
            private final Writer bounded =
                    new Writer() {
                        private void account(int length) throws IOException {
                            if (length > limits.maxDocumentChars() - chars)
                                throw new IOException("JSON document character budget exceeded");
                            chars += length;
                        }

                        @Override
                        public void write(char[] data, int offset, int length) throws IOException {
                            account(length);
                            utf8.write(data, offset, length);
                        }

                        @Override
                        public void write(String value, int offset, int length) throws IOException {
                            account(length);
                            utf8.write(value, offset, length);
                        }

                        @Override
                        public void write(int value) throws IOException {
                            account(1);
                            utf8.write(value);
                        }

                        @Override
                        public void flush() throws IOException {
                            utf8.flush();
                        }

                        @Override
                        public void close() throws IOException {
                            flush();
                        }
                    };

            @Override
            public void writeArray(Iterable<? extends V> values) throws IOException {
                if (closed || failed)
                    throw new IllegalStateException("JSON encoder is closed or failed");
                try {
                    Objects.requireNonNull(values, "values");
                    chars = 0;
                    if (values instanceof Collection<?> collection && collection.isEmpty()) {
                        bounded.write("[]");
                        bounded.flush();
                        return;
                    }
                    if (codec instanceof GeneratedJsonWriter<?> direct) {
                        @SuppressWarnings("unchecked")
                        GeneratedJsonWriter<V> generated = (GeneratedJsonWriter<V>) direct;
                        var writer = new JsonTokenWriter(bounded, limits);
                        writer.beginArray();
                        int count = 0;
                        for (V value : values) {
                            if (count++ >= limits.maxArrayElements())
                                throw new IOException("JSON array element budget exceeded");
                            if ((visitedValues++ & 255) == 0) checkpoint.run();
                            generated.writeValue(
                                    writer,
                                    Objects.requireNonNull(value, "Null snapshot value"),
                                    limits);
                        }
                        writer.endArray();
                        writer.flush();
                        return;
                    }
                    JsonWriter writer = new DepthLimitedWriter(bounded, limits.maxDepth());
                    writer.setStrictness(Strictness.STRICT);
                    writer.setSerializeNulls(true);
                    writer.beginArray();
                    int count = 0;
                    for (V value : values) {
                        if (count++ >= limits.maxArrayElements())
                            throw new IOException("JSON array element budget exceeded");
                        if ((visitedValues++ & 255) == 0) checkpoint.run();
                        codec.writeValue(
                                writer,
                                Objects.requireNonNull(value, "Null snapshot value"),
                                limits);
                    }
                    writer.endArray();
                    writer.flush();
                } catch (IOException | RuntimeException failure) {
                    failed = true;
                    throw failure;
                }
            }

            @Override
            public void close() throws IOException {
                if (!closed) {
                    closed = true;
                    if (!failed) utf8.flush();
                }
            }
        };
    }

    private static final class DepthLimitedWriter extends JsonWriter {
        private final int limit;
        private int depth;

        DepthLimitedWriter(Writer target, int limit) {
            super(target);
            this.limit = limit;
        }

        private void enter() throws IOException {
            if (depth >= limit) throw new IOException("JSON nesting budget exceeded");
            depth++;
        }

        @Override
        public JsonWriter beginArray() throws IOException {
            enter();
            return super.beginArray();
        }

        @Override
        public JsonWriter beginObject() throws IOException {
            enter();
            return super.beginObject();
        }

        @Override
        public JsonWriter endArray() throws IOException {
            super.endArray();
            depth--;
            return this;
        }

        @Override
        public JsonWriter endObject() throws IOException {
            super.endObject();
            depth--;
            return this;
        }
    }
}

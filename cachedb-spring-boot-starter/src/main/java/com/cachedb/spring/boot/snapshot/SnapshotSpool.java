package com.reactor.cachedb.spring.boot.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.core.codec.JsonArrayCodec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Preparation is disk-backed. Only one published batch is materialized as strings. */
final class SnapshotSpool implements AutoCloseable {
    record Entry(String id, long offset, int bytes, String partition, double score, String contentDigest) {
        Entry(String id, long offset, int bytes, String partition, double score) {
            this(id, offset, bytes, partition, score, null);
        }
    }

    private final Path path;
    private final FileChannel channel;
    private final List<Entry> entries = new ArrayList<>();
    private long bytes;

    SnapshotSpool(Path directory) throws IOException {
        path = Files.createTempFile(directory, "cachedb-snapshot-", ".spool");
        try {
            channel =
                    FileChannel.open(
                            path,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.DELETE_ON_CLOSE);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    <R, V> void prepare(
            SnapshotPlan<R, V> plan, SnapshotRows rows, ObjectMapper mapper, Runnable check)
            throws IOException {
        prepare(
                plan,
                rows,
                plan.jsonCodec() != null
                        ? plan.jsonCodec()
                        : new JacksonJsonArrayCodec<>(mapper, plan.valueType()),
                check);
    }

    <R, V> void prepare(
            SnapshotPlan<R, V> plan, SnapshotRows rows, JsonArrayCodec<V> codec, Runnable check)
            throws IOException {
        Function<R, Iterable<V>> projection = plan.projection().apply(rows);
        try (RootWriter<R, V> writer = new RootWriter<>(plan, projection, codec, check)) {
            for (R root : rows.get(plan.roots())) writer.write(root);
        }
    }

    <R, V> void prepareStreaming(
            javax.sql.DataSource source,
            SnapshotPlan<R, V> plan,
            SnapshotSettings settings,
            ObjectMapper mapper,
            Runnable check,
            List<String> ids)
            throws IOException {
        prepareStreaming(
                source,
                plan,
                settings,
                plan.jsonCodec() != null
                        ? plan.jsonCodec()
                        : new JacksonJsonArrayCodec<>(mapper, plan.valueType()),
                check,
                ids);
    }

    <R, V> void prepareStreaming(
            javax.sql.DataSource source,
            SnapshotPlan<R, V> plan,
            SnapshotSettings settings,
            JsonArrayCodec<V> codec,
            Runnable check,
            List<String> ids)
            throws IOException {
        try (RootWriter<R, V> writer =
                new RootWriter<>(plan, plan.projection().apply(null), codec, check)) {
            SnapshotJdbcReader.stream(
                    source,
                    plan,
                    settings,
                    check,
                    ids,
                    root -> {
                        try {
                            writer.write(root);
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    });
        }
    }

    private final class RootWriter<R, V> implements AutoCloseable {
        private final SnapshotPlan<R, V> plan;
        private final Function<R, Iterable<V>> projection;
        private final Runnable check;
        private final OutputStream counted;
        private final JsonArrayCodec.ArrayEncoder<V> encoder;
        private final Set<String> ids = new HashSet<>();
        private int roots;
        private final java.security.MessageDigest digest;

        RootWriter(
                SnapshotPlan<R, V> plan,
                Function<R, Iterable<V>> projection,
                JsonArrayCodec<V> codec,
                Runnable check)
                throws IOException {
            this.plan = plan;
            this.projection = projection;
            this.check = check;
            digest = plan.contentReuse() ? SnapshotPublication.sha256() : null;
            var buffer = new BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024);
            counted =
                    new FilterOutputStream(buffer) {
                        long checked;

                        private void checkpoint() {
                            if (bytes - checked >= 65536) {
                                check.run();
                                checked = bytes;
                            }
                        }

                        @Override
                        public void write(int value) throws IOException {
                            out.write(value);
                            if (digest != null) digest.update((byte) value);
                            bytes++;
                            checkpoint();
                        }

                        @Override
                        public void write(byte[] data, int offset, int length) throws IOException {
                            out.write(data, offset, length);
                            if (digest != null) digest.update(data, offset, length);
                            bytes += length;
                            checkpoint();
                        }
                    };
            encoder = codec.openEncoder(counted, check);
        }

        void write(R root) throws IOException {
            if ((roots++ & 63) == 0) check.run();
            String id = Objects.requireNonNull(plan.id().apply(root), "Null snapshot root id");
            if (id.isBlank() || id.length() > 512 || id.indexOf('\n') >= 0 || !ids.add(id))
                throw new IllegalStateException("Invalid or duplicate snapshot root identity");
            long start = bytes;
            if (digest != null) {
                digest.reset();
                digest.update(id.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            encoder.writeArray(
                    Objects.requireNonNull(projection.apply(root), "Null snapshot projection"));
            long length = bytes - start;
            if (length > Integer.MAX_VALUE - 8L)
                throw new IllegalStateException("Snapshot response exceeds Java String capacity");
            String partition =
                    plan.indexSpec() == null
                            ? null
                            : SnapshotIndexSpec.partition(
                                    plan.indexSpec().partitionBy().apply(root));
            double score =
                    plan.indexSpec() == null ? 0 : plan.indexSpec().scoreBy().applyAsDouble(root);
            if (!Double.isFinite(score))
                throw new IllegalArgumentException("Snapshot score must be finite");
            entries.add(new Entry(id, start, (int) length, partition, score,
                    digest == null ? null : HexFormat.of().formatHex(digest.digest())));
        }

        public void close() throws IOException {
            encoder.close();
            counted.flush();
            check.run();
        }
    }

    List<Entry> entries() {
        return entries;
    }

    long bytes() {
        return bytes;
    }

    String read(Entry entry, Runnable check) throws IOException {
        check.run();
        ByteBuffer buffer = ByteBuffer.allocate(entry.bytes());
        while (buffer.hasRemaining()) {
            check.run();
            if (channel.read(buffer, entry.offset() + buffer.position()) <= 0)
                throw new EOFException("Incomplete snapshot spool");
        }
        return new String(buffer.array(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close();
        } finally {
            Files.deleteIfExists(path);
        }
    }
}

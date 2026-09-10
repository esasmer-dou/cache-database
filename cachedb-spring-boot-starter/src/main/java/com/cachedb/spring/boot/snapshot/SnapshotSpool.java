package com.reactor.cachedb.spring.boot.snapshot;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

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
    record Entry(String id, long offset, int bytes) {}

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
        Function<R, Iterable<V>> projection = plan.projection().apply(rows);
        ObjectWriter writer =
                mapper.writerFor(plan.valueType())
                        .without(
                                com.fasterxml.jackson.databind.SerializationFeature
                                        .FLUSH_AFTER_WRITE_VALUE);
        Set<String> ids = new HashSet<>();
        var buffer = new BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024);
        OutputStream counted =
                new FilterOutputStream(buffer) {
                    @Override
                    public void write(int value) throws IOException {
                        check.run();
                        out.write(value);
                        bytes++;
                    }

                    @Override
                    public void write(byte[] data, int offset, int length) throws IOException {
                        check.run();
                        out.write(data, offset, length);
                        bytes += length;
                    }
                };
        for (R root : rows.get(plan.roots())) {
            check.run();
            String id = Objects.requireNonNull(plan.id().apply(root), "Null snapshot root id");
            if (id.isBlank() || id.length() > 512 || !ids.add(id))
                throw new IllegalStateException("Invalid or duplicate snapshot root identity");
            long start = bytes;
            try (JsonGenerator json = mapper.getFactory().createGenerator(counted)) {
                json.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
                json.writeStartArray();
                for (V value :
                        Objects.requireNonNull(
                                projection.apply(root), "Null snapshot projection")) {
                    check.run();
                    writer.writeValue(json, Objects.requireNonNull(value, "Null snapshot value"));
                }
                json.writeEndArray();
            }
            long length = bytes - start;
            if (length > Integer.MAX_VALUE - 8L)
                throw new IllegalStateException("Snapshot response exceeds Java String capacity");
            entries.add(new Entry(id, start, (int) length));
        }
        counted.flush();
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

package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Captured runtime ciphertext/nonces, never plaintext protected columns or plaintext hashes. */
final class InitialBootstrapPageCodec {
    private InitialBootstrapPageCodec() {}
    static <T, ID> byte[] encode(InitialSourceHotWindow<T, ID> window, Page<T, ID> page) {
        try {
            var buffer = new BoundedOutput(window.pageBytes());
            try (var out = new DataOutputStream(buffer)) {
                out.writeInt(1); out.writeLong(page.id().getMostSignificantBits()); out.writeLong(page.id().getLeastSignificantBits());
                out.writeBoolean(page.last()); out.writeInt(page.rows().size());
                for (var row : page.rows()) {
                    string(out, row.proof().canonicalId(), 1024); out.writeLong(row.proof().sourceVersion());
                    out.writeUTF(row.proof().digest()); string(out, row.redisPayload(), window.rowBytes());
                }
            }
            return buffer.toByteArray();
        } catch (IOException malformed) { throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS); }
    }
    static <T, ID> Page<T, ID> decode(InitialSourceHotWindow<T, ID> window, Session session, byte[] bytes) {
        if (bytes.length < 1 || bytes.length > window.pageBytes()) invalid();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) invalid();
            UUID page = new UUID(in.readLong(), in.readLong()); boolean last = in.readBoolean(); int size = in.readInt();
            if (size < 0 || size > 128) invalid();
            var rows = new ArrayList<Row<T, ID>>(size); var ids = new HashSet<String>();
            for (int i = 0; i < size; i++) {
                String canonical = string(in, 1024); long version = in.readLong(); String digest = in.readUTF();
                String payload = string(in, window.rowBytes());
                if (!ids.add(canonical) || !digest.equals(InitialBootstrapProof.row(window, session, canonical, version, payload))) invalid();
                ID id = window.ids().decode(canonical);
                T entity = window.codec().fromRedisValue(payload);
                if (!canonical.equals(window.canonical(window.metadata().idAccessor().apply(entity)))
                        || !session.request().tenant().equals(window.metadata().extractTenantId(entity))) invalid();
                rows.add(new Row<>(id, entity, new RowRef(canonical, version, digest), payload,
                        Math.toIntExact(com.reactor.cachedb.core.codec.Utf8Length.of(payload))));
            }
            if (in.read() != -1) invalid();
            return new Page<>(page, rows, last);
        } catch (IOException malformed) { throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS); }
    }
    private static void string(DataOutputStream out, String value, int max) throws IOException {
        if (value == null || com.reactor.cachedb.core.codec.Utf8Length.of(value) > max) invalid();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in, int max) throws IOException {
        int length = in.readInt(); if (length < 0 || length > max || length > in.available()) invalid();
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(in.readNBytes(length))).toString();
    }
    private static final class BoundedOutput extends ByteArrayOutputStream {
        private final int max;
        BoundedOutput(int max) { super(Math.min(4096, max)); this.max = max; }
        @Override public void write(int value) {
            if (count == max) invalid(); super.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) {
            if (length < 0 || count > max - length) invalid(); super.write(bytes, offset, length);
        }
    }
    private static void invalid() { throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS); }
}

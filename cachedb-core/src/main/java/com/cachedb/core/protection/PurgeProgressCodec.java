package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;

/** Fixed versioned bounded binary journal; never Java serialization/JSON/reflection. */
public final class PurgeProgressCodec {
    public static final int MAX_BYTES = 32 * 1024;
    private PurgeProgressCodec() {}
    public static byte[] encode(Progress p) {
        try {
            var bytes = new ByteArrayOutputStream(2048);
            var out = new DataOutputStream(bytes);
            out.writeInt(0x43445031);
            out.writeLong(p.revision()); uuid(out, p.operationId());
            out.writeLong(p.closedGeneration()); out.writeLong(p.newGeneration());
            out.writeByte(p.stage().ordinal()); out.writeBoolean(p.includeSource());
            text(out, p.operatorToken()); instant(out, p.startedAt()); instant(out, p.completedAt());
            out.writeLong(p.redisRows()); out.writeLong(p.redisKeys()); out.writeLong(p.sourceRows());
            text(out, p.inventoryToken()); text(out, p.cursor());
            out.writeBoolean(p.pending() != null);
            if (p.pending() != null) {
                uuid(out, p.pending().id()); text(out, p.pending().nextCursor());
                out.writeBoolean(p.pending().last()); out.writeInt(p.pending().ids().size());
                for (String id : p.pending().ids()) text(out, id);
            }
            out.writeInt(p.pendingOffset()); text(out, p.bindingFingerprint());
            if (bytes.size() > MAX_BYTES) invalid();
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS); }
    }
    public static Progress decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) invalid();
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0x43445031) invalid();
            long revision = in.readLong(); UUID operation = uuid(in);
            long generation = in.readLong(), activation = in.readLong();
            int stage = in.readUnsignedByte();
            if (stage >= Stage.values().length) invalid();
            boolean source = in.readBoolean(); String operator = text(in, 64);
            Instant started = instant(in), complete = instant(in);
            long rows = in.readLong(), keys = in.readLong(), sql = in.readLong();
            String inventory = text(in, 64), cursor = text(in, 64);
            Page pending = null;
            if (in.readBoolean()) {
                UUID page = uuid(in); String next = text(in, 8192); boolean last = in.readBoolean();
                int count = in.readInt();
                if (count < 1 || count > 128) invalid();
                var ids = new ArrayList<String>(count);
                for (int i = 0; i < count; i++) ids.add(text(in, 64));
                pending = new Page(page, next, last, ids);
            }
            int offset = in.readInt(); String fingerprint = text(in, 64);
            if (in.available() != 0) invalid();
            return new Progress(revision, operation, generation, activation, Stage.values()[stage], source,
                    operator, started, complete, rows, keys, sql, inventory, cursor, pending, offset, fingerprint);
        } catch (IOException | RuntimeException corrupt) {
            throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS);
        }
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 8192) invalid();
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in, int max) throws IOException {
        int length = in.readInt();
        if (length == -1) return null;
        if (length < 0 || length > max) invalid();
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) invalid();
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void instant(DataOutputStream out, Instant value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) { out.writeLong(value.getEpochSecond()); out.writeInt(value.getNano()); }
    }
    private static Instant instant(DataInputStream in) throws IOException {
        return in.readBoolean() ? Instant.ofEpochSecond(in.readLong(), in.readInt()) : null;
    }
    private static void invalid() { throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS); }
}

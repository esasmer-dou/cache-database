package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.SourceOwnerOnboarding.*;
import java.io.*;
import java.util.ArrayList;
import java.util.UUID;

/** Bounded transient administrative journal. No Java serialization or raw-identity diagnostics. */
final class SourceOwnerCheckpointCodec {
    static final int MAX_BYTES = 262144;
    private SourceOwnerCheckpointCodec() {}
    static byte[] encode(Checkpoint value) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(1); out.writeLong(value.revision()); uuid(out, value.operationId());
                out.writeUTF(value.planToken()); out.writeLong(value.generation()); out.writeUTF(value.operatorToken());
                out.writeByte(value.state().ordinal()); out.writeBoolean(value.inventoryToken() != null);
                if (value.inventoryToken() != null) out.writeUTF(value.inventoryToken());
                out.writeUTF(value.cursor()); out.writeBoolean(value.last());
                out.writeLong(value.examined()); out.writeLong(value.owned()); out.writeLong(value.absent()); out.writeLong(value.unknown());
                out.writeBoolean(value.pending() != null);
                if (value.pending() != null) {
                    Page page = value.pending(); uuid(out, page.id()); out.writeUTF(page.nextCursor());
                    out.writeBoolean(page.last()); out.writeInt(page.ids().size());
                    for (String id : page.ids()) out.writeUTF(id);
                }
            }
            if (bytes.size() > MAX_BYTES) invalid();
            return bytes.toByteArray();
        } catch (IOException malformed) { throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS); }
    }
    static Checkpoint decode(byte[] bytes) {
        if (bytes.length < 1 || bytes.length > MAX_BYTES) invalid();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) invalid();
            long revision = in.readLong(); UUID operation = uuid(in);
            String plan = in.readUTF(); long generation = in.readLong(); String operator = in.readUTF();
            int state = in.readUnsignedByte();
            if (state >= State.values().length) invalid();
            String inventory = in.readBoolean() ? in.readUTF() : null;
            String cursor = in.readUTF(); boolean last = in.readBoolean();
            long examined = in.readLong(), owned = in.readLong(), absent = in.readLong(), unknown = in.readLong();
            Page pending = null;
            if (in.readBoolean()) {
                UUID id = uuid(in); String next = in.readUTF(); boolean terminal = in.readBoolean(); int size = in.readInt();
                if (size < 0 || size > SourceOwnerOnboarding.CHUNK) invalid();
                var ids = new ArrayList<String>(size);
                for (int i = 0; i < size; i++) ids.add(in.readUTF());
                pending = new Page(id, next, terminal, ids);
            }
            if (in.read() != -1) invalid();
            return new Checkpoint(revision, operation, plan, generation, operator, State.values()[state],
                    inventory, cursor, pending, last, examined, owned, absent, unknown);
        } catch (IOException malformed) { throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS); }
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void invalid() { throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS); }
}

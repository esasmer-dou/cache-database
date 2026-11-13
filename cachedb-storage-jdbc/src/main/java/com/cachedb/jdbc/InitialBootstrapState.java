package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import java.io.*;
import java.util.UUID;

/** Fixed-size administrative state. Source payloads live only in bounded encrypted page rows. */
final class InitialBootstrapState {
    static final int MAX_BYTES = 16384;
    UUID operation;
    long generation, revision, probes, warmed, residentBytes;
    String plan, manifest, operator, runId, roster, inventory, runtimeProof, cursor;
    String windowDigest;
    int pages, verifiedPages;
    Phase phase;
    Activation activation;
    boolean finalizing;

    static InitialBootstrapState initial(Request request, String plan, String manifest) {
        var s = new InitialBootstrapState();
        s.operation = request.operationId(); s.generation = request.generation().value();
        s.plan = plan; s.manifest = manifest; s.operator = request.operatorToken(); s.phase = Phase.CLOSURE;
        s.windowDigest = ProtectionKey.hash("initial-window-v1:" + plan);
        return s;
    }
    WindowProof window() { return new WindowProof(warmed, windowDigest); }
    Progress progress(long highwater) { return new Progress(phase, probes, warmed, highwater, plan); }

    byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(2048);
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(2); uuid(out, operation); out.writeLong(generation); out.writeLong(revision);
                text(out, plan); text(out, manifest); text(out, operator); out.writeUTF(phase.name());
                text(out, runId); text(out, roster); text(out, inventory); text(out, runtimeProof);
                text(out, cursor); text(out, windowDigest); out.writeLong(probes); out.writeLong(warmed);
                out.writeLong(residentBytes); out.writeInt(pages); out.writeInt(verifiedPages);
                out.writeBoolean(activation != null);
                if (activation != null) {
                    text(out, activation.token()); text(out, activation.runtimeProof());
                    var r = activation.range();
                    out.writeLong(r.controlEpoch()); out.writeLong(r.reservationEpoch());
                    out.writeLong(r.first()); out.writeLong(r.last()); uuid(out, r.request());
                }
                out.writeBoolean(finalizing);
            }
            if (bytes.size() > MAX_BYTES) invalid();
            return bytes.toByteArray();
        } catch (IOException malformed) { throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS); }
    }
    static InitialBootstrapState decode(byte[] bytes, ProtectionKey key) {
        if (bytes.length < 1 || bytes.length > MAX_BYTES) invalid();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int format = in.readInt();
            if (format != 1 && format != 2) invalid();
            var s = new InitialBootstrapState();
            s.operation = uuid(in); s.generation = in.readLong(); s.revision = in.readLong();
            s.plan = text(in); s.manifest = text(in); s.operator = text(in); s.phase = Phase.valueOf(in.readUTF());
            s.runId = text(in); s.roster = text(in); s.inventory = text(in); s.runtimeProof = text(in);
            s.cursor = text(in); s.windowDigest = text(in); s.probes = in.readLong(); s.warmed = in.readLong();
            s.residentBytes = in.readLong(); s.pages = in.readInt(); s.verifiedPages = in.readInt();
            if (s.generation < 1 || s.revision < 0 || s.probes < 0 || s.probes > 2 || s.warmed < 0 || s.warmed > 10000
                    || s.pages < 0 || s.pages > InitialSourceHotWindow.MAX_PAGES || s.verifiedPages < 0 || s.verifiedPages > s.pages
                    || s.residentBytes < 0 || s.residentBytes > 67108864) invalid();
            ProtectionKey.token(s.plan); ProtectionKey.token(s.manifest); ProtectionKey.token(s.operator);
            ProtectionKey.token(s.windowDigest);
            if (s.inventory != null) ProtectionKey.token(s.inventory);
            if (s.runtimeProof != null) ProtectionKey.token(s.runtimeProof);
            if (s.runId != null && !s.runId.matches("[0-9a-f]{40}")) invalid();
            if (s.roster != null) ProtectionKey.token(s.roster);
            if (s.cursor != null) SourceOwnerOnboarding.canonicalId(s.cursor);
            if (in.readBoolean()) {
                String token = text(in), runtime = text(in);
                long control = in.readLong(), epoch = in.readLong(), first = in.readLong(), last = in.readLong();
                UUID request = uuid(in);
                var range = new TenantVersionRange(key, s.generation, control, epoch, first, last, request, s.runId, s.inventory);
                s.activation = new Activation(token, s.plan, s.manifest, s.inventory, s.window(), runtime, range);
            }
            s.finalizing = format == 2 && in.readBoolean();
            if (s.finalizing && s.phase != Phase.SQL_OPEN && s.phase != Phase.ACTIVE) invalid();
            if (in.read() != -1 || (s.phase == Phase.SQL_OPEN || s.phase == Phase.ACTIVE) && s.activation == null) invalid();
            return s;
        } catch (IOException | IllegalArgumentException malformed) {
            throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS);
        }
    }
    static void text(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null); if (value != null) out.writeUTF(value);
    }
    static String text(DataInputStream in) throws IOException { return in.readBoolean() ? in.readUTF() : null; }
    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void invalid() { throw new ProtectionException(ProtectionException.Code.INVALID_PROGRESS); }
}

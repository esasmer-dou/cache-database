package com.reactor.cachedb.core.protection;

import com.reactor.cachedb.core.protection.InitialTenantBootstrap.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Pending proofs never contain an unkeyed hash of encrypted logical/source plaintext. */
public final class InitialBootstrapProof {
    private InitialBootstrapProof() {}
    public static <T, ID> String row(InitialSourceHotWindow<T, ID> window, Session session,
            String canonicalId, long version, String capturedRedisPayload) {
        InitialTenantBootstrap.positiveVersion(version);
        if (capturedRedisPayload == null || com.reactor.cachedb.core.codec.Utf8Length.of(capturedRedisPayload) > window.rowBytes())
            throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        // Payload was already encoded/encrypted ONCE by the actual bound runtime codec.
        String cipherHash = bytes(capturedRedisPayload.getBytes(StandardCharsets.UTF_8));
        var parts = List.of("initial-source-row-v1", session.planToken(), session.manifestToken(),
                session.request().operationId().toString(), session.lease().key().tenantToken(),
                Long.toString(session.request().generation().value()), canonicalId, Long.toString(version), cipherHash);
        if (window.codec().encryptedMapping()) {
            String mac = window.codec().encryptionIntentFingerprint(parts);
            if (mac == null || !mac.matches("~I1:[A-Za-z0-9_-]{43}"))
                throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
            return ProtectionKey.hash(mac); // HMAC output only; no plaintext dictionary oracle.
        }
        return ProtectionKey.hash(String.join(":", parts));
    }
    public static String bytes(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static <T, ID> void sameLogicalSource(InitialSourceHotWindow<T, ID> window, T fresh, T captured) {
        if (!window.canonical(window.metadata().idAccessor().apply(fresh))
                .equals(window.canonical(window.metadata().idAccessor().apply(captured)))
                || !Objects.equals(window.metadata().extractTenantId(fresh), window.metadata().extractTenantId(captured)))
            throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
        for (String column : window.metadata().columns()) {
            if (!Objects.deepEquals(window.codec().entityColumnValue(fresh, column),
                    window.codec().entityColumnValue(captured, column)))
                throw new ProtectionException(ProtectionException.Code.MIGRATION_REQUIRED);
        }
    }
}

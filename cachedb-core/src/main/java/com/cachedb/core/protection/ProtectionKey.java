package com.reactor.cachedb.core.protection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Pseudonymous scope and tenant keys. Raw identity is never retained here. */
public record ProtectionKey(String scopeToken, String tenantToken) implements Comparable<ProtectionKey> {
    public ProtectionKey {
        token(scopeToken); token(tenantToken);
    }
    public static ProtectionKey of(String scope, String tenant) {
        if (scope == null || !scope.matches("[A-Za-z0-9._:-]{1,128}")) invalid();
        checkTenant(tenant);
        return new ProtectionKey(hash(scope), hash(scope + "\0" + tenant));
    }
    public static String hash(String input) {
        if (input == null || input.length() > 4096) invalid();
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(input));
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(encoded);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.nio.charset.CharacterCodingException malformed) {
            throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static String token(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) invalid();
        return value;
    }
    public static void checkTenant(String value) {
        if (value == null || value.isBlank() || value.length() > 256) invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) invalid();
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) invalid();
            } else if (Character.isLowSurrogate(c)) invalid();
        }
    }
    /** Canonical typed-ID hash for the bounded purge-only worklist, never a foreground identity reservation. */
    public String objectToken(String canonicalId) {
        if (canonicalId == null || canonicalId.isEmpty() || canonicalId.length() > 1024) invalid();
        // hash() validates Unicode before any identity is accepted.
        String value = scopeToken + ":" + tenantToken + ":" + canonicalId;
        String token = hash(value);
        byte[] bytes = canonicalId.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024) invalid();
        return token;
    }
    /** Logical suffix only; the configured key strategy must co-locate control keys with guarded FCALL keys. */
    public String progressKeySuffix() { return "purge:" + scopeToken + ":" + tenantToken; }
    @Override public int compareTo(ProtectionKey other) {
        int scope = scopeToken.compareTo(other.scopeToken);
        return scope == 0 ? tenantToken.compareTo(other.tenantToken) : scope;
    }
    private static void invalid() { throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST); }
}

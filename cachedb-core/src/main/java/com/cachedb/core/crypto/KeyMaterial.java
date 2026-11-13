package com.reactor.cachedb.core.crypto;

import static com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.INVALID_CONFIGURATION;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Immutable, defensively copied AES-256 and independent HMAC-SHA256 key pair. */
public final class KeyMaterial {
    public static final int KEY_BYTES = 32;

    private final String keyId;
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec blindIndexKey;

    public KeyMaterial(String keyId, byte[] encryptionKey, byte[] blindIndexKey) {
        CryptoSupport.name(keyId);
        if (encryptionKey == null || encryptionKey.length != KEY_BYTES
                || blindIndexKey == null || blindIndexKey.length != KEY_BYTES
                || MessageDigest.isEqual(encryptionKey, blindIndexKey)) {
            throw new CacheEncryptionException(INVALID_CONFIGURATION);
        }
        this.keyId = keyId;
        this.encryptionKey = new SecretKeySpec(encryptionKey, "AES");
        this.blindIndexKey = new SecretKeySpec(blindIndexKey, "HmacSHA256");
    }

    /** Canonical standard Base64, padded, exactly 32 decoded bytes per key. Dev/test config only. */
    public static KeyMaterial fromBase64(String keyId, String encryptionKey, String blindIndexKey) {
        CryptoSupport.name(keyId);
        byte[] aes = null;
        byte[] hmac = null;
        try {
            aes = decodeKey(encryptionKey);
            hmac = decodeKey(blindIndexKey);
            return new KeyMaterial(keyId, aes, hmac);
        } finally {
            if (aes != null) Arrays.fill(aes, (byte) 0);
            if (hmac != null) Arrays.fill(hmac, (byte) 0);
        }
    }

    private static byte[] decodeKey(String encoded) {
        if (encoded == null || encoded.length() != 44) {
            throw new CacheEncryptionException(INVALID_CONFIGURATION);
        }
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) { throw new CacheEncryptionException(INVALID_CONFIGURATION); }
        if (decoded.length != KEY_BYTES || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
            Arrays.fill(decoded, (byte) 0);
            throw new CacheEncryptionException(INVALID_CONFIGURATION);
        }
        return decoded;
    }

    public String keyId() { return keyId; }
    SecretKey encryptionKey() { return encryptionKey; }
    SecretKey blindIndexKey() { return blindIndexKey; }
    boolean sameEncryptionKey(KeyMaterial other) { return encryptionKey.equals(other.encryptionKey); }
    boolean sameBlindIndexKey(KeyMaterial other) { return blindIndexKey.equals(other.blindIndexKey); }

    boolean encryptionMatchesBlindIndex(KeyMaterial other) {
        byte[] aes = encryptionKey.getEncoded();
        byte[] hmac = other.blindIndexKey.getEncoded();
        try { return MessageDigest.isEqual(aes, hmac); }
        finally {
            Arrays.fill(aes, (byte) 0);
            Arrays.fill(hmac, (byte) 0);
        }
    }

    @Override public String toString() { return "KeyMaterial[redacted]"; }
}

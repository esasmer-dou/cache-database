package com.reactor.cachedb.core.crypto;

import static com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.*;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Thread-safe bounded string-field engine using only JCA. No reflection, remote I/O or executors.
 * All non-null decrypts require E1 framing. Legacy plaintext is never an encrypted-field fallback.
 * Namespace must already bind tenant identity if row ids are not globally unique.
 */
public final class JcaFieldEncryption {
    public static final int MAX_FIELD_BYTES = 64 * 1024;
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BITS = 128;
    public static final int TAG_BYTES = TAG_BITS / 8;
    public static final int DEFAULT_MAX_CONCURRENT = 64;
    public static final int HARD_MAX_CONCURRENT = 256;
    public static final int MAX_KEY_ID_CHARS = CryptoSupport.MAX_NAME_CHARS;
    public static final int MAX_NAMESPACE_BYTES = CryptoSupport.MAX_NAMESPACE_BYTES;
    public static final int MAX_ID_BYTES = CryptoSupport.MAX_ID_BYTES;
    public static final int MAX_COLUMN_BYTES = CryptoSupport.MAX_COLUMN_BYTES;
    private static final String PREFIX = "~E1:";
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final CacheKeyProvider provider;
    private final SecureRandom random = new SecureRandom();
    private final Semaphore permits;

    public JcaFieldEncryption(CacheKeyProvider provider) { this(provider, DEFAULT_MAX_CONCURRENT); }

    public JcaFieldEncryption(CacheKeyProvider provider, int maxConcurrent) {
        if (provider == null || maxConcurrent < 1 || maxConcurrent > HARD_MAX_CONCURRENT) {
            throw new CacheEncryptionException(INVALID_CONFIGURATION);
        }
        this.provider = provider;
        this.permits = new Semaphore(maxConcurrent);
    }

    /** The 64 KiB cap includes the entire ASCII envelope, not just decoded plaintext. */
    public static int maxPlaintextBytes(String keyId) {
        CryptoSupport.name(keyId);
        int encodedBudget = MAX_FIELD_BYTES - PREFIX.length() - keyId.length() - 1;
        return encodedBudget * 3 / 4 - NONCE_BYTES - TAG_BYTES;
    }

    public String encrypt(String keyRing, String namespace, String id, String column, String plaintext) {
        CryptoSupport.name(keyRing);
        CryptoSupport.context(namespace, id, column);
        if (plaintext == null) return null;
        int byteLength = CryptoSupport.textLength(plaintext, MAX_FIELD_BYTES);
        acquire();
        byte[] clear = null;
        try {
            KeyMaterial key = current(keyRing);
            if (byteLength > maxPlaintextBytes(key.keyId())) throw new CacheEncryptionException(FIELD_LIMIT);
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            byte[] packed = new byte[NONCE_BYTES + byteLength + TAG_BYTES];
            System.arraycopy(nonce, 0, packed, 0, NONCE_BYTES);
            clear = plaintext.getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key.encryptionKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(CryptoSupport.aad(namespace, id, column));
            int written = cipher.doFinal(clear, 0, clear.length, packed, NONCE_BYTES);
            if (written != byteLength + TAG_BYTES) throw new CacheEncryptionException(CRYPTO_FAILURE);
            String envelope = PREFIX + key.keyId() + ':' + BASE64URL.encodeToString(packed);
            if (envelope.length() > MAX_FIELD_BYTES) throw new CacheEncryptionException(FIELD_LIMIT);
            return envelope;
        } catch (GeneralSecurityException | java.security.ProviderException failure) {
            throw new CacheEncryptionException(CRYPTO_FAILURE);
        } finally {
            if (clear != null) Arrays.fill(clear, (byte) 0);
            permits.release();
        }
    }

    public String decrypt(String keyRing, String namespace, String id, String column, String envelope) {
        CryptoSupport.name(keyRing);
        CryptoSupport.context(namespace, id, column);
        if (envelope == null) return null;
        if (envelope.length() > MAX_FIELD_BYTES) throw new CacheEncryptionException(FIELD_LIMIT);
        if (!envelope.startsWith(PREFIX)) throw new CacheEncryptionException(INVALID_ENVELOPE);
        int separator = envelope.indexOf(':', PREFIX.length());
        if (separator <= PREFIX.length() || separator > PREFIX.length() + MAX_KEY_ID_CHARS) {
            throw new CacheEncryptionException(INVALID_ENVELOPE);
        }
        String keyId = envelope.substring(PREFIX.length(), separator);
        try { CryptoSupport.name(keyId); }
        catch (CacheEncryptionException invalid) { throw new CacheEncryptionException(INVALID_ENVELOPE); }
        CryptoSupport.canonicalBase64Url(envelope, separator + 1);
        acquire();
        byte[] clear = null;
        try {
            byte[] packed = Base64.getUrlDecoder().decode(envelope.substring(separator + 1));
            if (packed.length < NONCE_BYTES + TAG_BYTES) throw new CacheEncryptionException(INVALID_ENVELOPE);
            KeyMaterial key = byId(keyRing, keyId);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key.encryptionKey(), new GCMParameterSpec(TAG_BITS, packed, 0, NONCE_BYTES));
            cipher.updateAAD(CryptoSupport.aad(namespace, id, column));
            clear = cipher.doFinal(packed, NONCE_BYTES, packed.length - NONCE_BYTES);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(clear)).toString();
        } catch (AEADBadTagException failure) {
            throw new CacheEncryptionException(AUTHENTICATION_FAILED);
        } catch (CharacterCodingException invalid) {
            throw new CacheEncryptionException(INVALID_TEXT);
        } catch (IllegalArgumentException invalid) {
            throw new CacheEncryptionException(INVALID_ENVELOPE);
        } catch (GeneralSecurityException | java.security.ProviderException failure) {
            throw new CacheEncryptionException(CRYPTO_FAILURE);
        } finally {
            if (clear != null) Arrays.fill(clear, (byte) 0);
            permits.release();
        }
    }

    /** EQ-only token: no row id, no AES key id. Ring/namespace/column are domain-separated. */
    public String blindIndex(String keyRing, String namespace, String column, String plaintext) {
        CryptoSupport.name(keyRing);
        CryptoSupport.component(namespace, MAX_NAMESPACE_BYTES);
        CryptoSupport.component(column, MAX_COLUMN_BYTES);
        if (plaintext == null) return null;
        CryptoSupport.textLength(plaintext, MAX_FIELD_BYTES);
        acquire();
        byte[] clear = null;
        try {
            KeyMaterial key = current(keyRing);
            clear = plaintext.getBytes(StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key.blindIndexKey());
            mac.update(CryptoSupport.blindDomain(keyRing, namespace, column));
            byte[] digest = mac.doFinal(clear);
            return "~B1:" + BASE64URL.encodeToString(digest);
        } catch (GeneralSecurityException | java.security.ProviderException failure) {
            throw new CacheEncryptionException(CRYPTO_FAILURE);
        } finally {
            if (clear != null) Arrays.fill(clear, (byte) 0);
            permits.release();
        }
    }

    public static final int MAX_INTENT_PARTS = 16_384;
    public static final int MAX_INTENT_BYTES = 4 * 1024 * 1024;

    /** Length framing distinguishes null/empty/delimiters. AES key ids and nonces are deliberately absent. */
    public String intentMac(String ring, String namespace, String purpose, java.util.List<String> parts) {
        CryptoSupport.name(ring);
        CryptoSupport.component(namespace, MAX_NAMESPACE_BYTES);
        CryptoSupport.name(purpose);
        if (parts == null || parts.size() > MAX_INTENT_PARTS) throw new CacheEncryptionException(FIELD_LIMIT);
        acquire();
        byte[] scratch = null;
        try {
            scratch = new byte[1024];
            KeyMaterial material;
            try { material = provider.current(ring); }
            catch (RuntimeException unavailable) { throw new CacheEncryptionException(INTENT_KEY_UNAVAILABLE); }
            if (material == null || material.blindIndexKey() == null)
                throw new CacheEncryptionException(INTENT_KEY_UNAVAILABLE);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(material.blindIndexKey());
            int bytes = intentPart(mac, "cachedb:protected-intent:v1", 0, scratch);
            bytes = intentPart(mac, ring, bytes, scratch);
            bytes = intentPart(mac, namespace, bytes, scratch);
            bytes = intentPart(mac, purpose, bytes, scratch);
            intentLength(mac, parts.size());
            bytes += 4;
            for (String part : parts) bytes = intentPart(mac, part, bytes, scratch);
            return "~I1:" + BASE64URL.encodeToString(mac.doFinal());
        } catch (CacheEncryptionException safe) { throw safe; }
        catch (GeneralSecurityException | java.security.ProviderException unsafe) {
            throw new CacheEncryptionException(INTENT_KEY_UNAVAILABLE);
        } catch (RuntimeException unsafe) { throw new CacheEncryptionException(INVALID_TEXT); }
        finally { if (scratch != null) Arrays.fill(scratch, (byte) 0); permits.release(); }
    }

    private static int intentPart(Mac mac, String value, int previous, byte[] scratch) {
        if (previous > MAX_INTENT_BYTES - 4) throw new CacheEncryptionException(FIELD_LIMIT);
        if (value == null) { intentLength(mac, -1); return previous + 4; }
        int length = CryptoSupport.textLength(value, MAX_INTENT_BYTES - previous - 4);
        intentLength(mac, length);
        int used = 0;
        for (int i = 0; i < value.length(); i++) {
            if (used > scratch.length - 4) { mac.update(scratch, 0, used); used = 0; }
            char c = value.charAt(i);
            if (c < 0x80) scratch[used++] = (byte) c;
            else if (c < 0x800) {
                scratch[used++] = (byte) (0xc0 | c >>> 6);
                scratch[used++] = (byte) (0x80 | c & 0x3f);
            } else if (Character.isHighSurrogate(c)) {
                int codePoint = Character.toCodePoint(c, value.charAt(++i));
                scratch[used++] = (byte) (0xf0 | codePoint >>> 18);
                scratch[used++] = (byte) (0x80 | codePoint >>> 12 & 0x3f);
                scratch[used++] = (byte) (0x80 | codePoint >>> 6 & 0x3f);
                scratch[used++] = (byte) (0x80 | codePoint & 0x3f);
            } else {
                scratch[used++] = (byte) (0xe0 | c >>> 12);
                scratch[used++] = (byte) (0x80 | c >>> 6 & 0x3f);
                scratch[used++] = (byte) (0x80 | c & 0x3f);
            }
        }
        if (used > 0) mac.update(scratch, 0, used);
        return previous + 4 + length;
    }

    private static void intentLength(Mac mac, int value) {
        mac.update((byte) (value >>> 24)); mac.update((byte) (value >>> 16));
        mac.update((byte) (value >>> 8)); mac.update((byte) value);
    }

    private void acquire() {
        if (!permits.tryAcquire()) throw new CacheEncryptionException(SATURATED);
    }

    private KeyMaterial current(String ring) {
        KeyMaterial material;
        try { material = provider.current(ring); }
        catch (RuntimeException failure) { throw new CacheEncryptionException(PROVIDER_FAILURE); }
        if (material == null) throw new CacheEncryptionException(PROVIDER_FAILURE);
        return material;
    }

    private KeyMaterial byId(String ring, String id) {
        Optional<KeyMaterial> material;
        try { material = provider.byId(ring, id); }
        catch (RuntimeException failure) { throw new CacheEncryptionException(PROVIDER_FAILURE); }
        if (material == null) throw new CacheEncryptionException(PROVIDER_FAILURE);
        KeyMaterial key = material.orElseThrow(() -> new CacheEncryptionException(UNKNOWN_KEY));
        if (!id.equals(key.keyId())) throw new CacheEncryptionException(PROVIDER_FAILURE);
        return key;
    }
}

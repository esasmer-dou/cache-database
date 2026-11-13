package com.reactor.cachedb.core.crypto;

import static com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.*;

import java.nio.charset.StandardCharsets;

/** Strict bounded framing, deliberately unlike replacement-based legacy UTF-8 codecs. */
final class CryptoSupport {
    static final int MAX_NAME_CHARS = 64;
    static final int MAX_NAMESPACE_BYTES = 1024;
    static final int MAX_ID_BYTES = 1024;
    static final int MAX_COLUMN_BYTES = 128;

    private CryptoSupport() {}

    static void name(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_NAME_CHARS || !alphaNumeric(value.charAt(0))) {
            throw new CacheEncryptionException(INVALID_CONFIGURATION);
        }
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!alphaNumeric(c) && c != '.' && c != '_' && c != '-') {
                throw new CacheEncryptionException(INVALID_CONFIGURATION);
            }
        }
    }

    private static boolean alphaNumeric(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }

    static int textLength(String value, int maxBytes) {
        if (value == null) throw new CacheEncryptionException(INVALID_TEXT);
        if (value.length() > maxBytes) throw new CacheEncryptionException(FIELD_LIMIT);
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new CacheEncryptionException(INVALID_TEXT);
                }
                bytes += 4;
            } else {
                if (Character.isLowSurrogate(c)) throw new CacheEncryptionException(INVALID_TEXT);
                bytes += 3;
            }
            if (bytes > maxBytes) throw new CacheEncryptionException(FIELD_LIMIT);
        }
        return bytes;
    }

    static void component(String value, int maxBytes) {
        if (value == null || value.isEmpty() || value.length() > maxBytes) {
            throw new CacheEncryptionException(INVALID_CONTEXT);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '|' || c < 0x20 || c == 0x7f) throw new CacheEncryptionException(INVALID_CONTEXT);
        }
        try { textLength(value, maxBytes); }
        catch (CacheEncryptionException invalid) { throw new CacheEncryptionException(INVALID_CONTEXT); }
    }

    static void context(String namespace, String id, String column) {
        component(namespace, MAX_NAMESPACE_BYTES);
        component(id, MAX_ID_BYTES);
        component(column, MAX_COLUMN_BYTES);
    }

    static byte[] aad(String namespace, String id, String column) {
        return (namespace + '|' + id + '|' + column).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] blindDomain(String keyRing, String namespace, String column) {
        return ("CacheDB:blind-index:v1|" + keyRing + '|' + namespace + '|' + column + '|')
                .getBytes(StandardCharsets.UTF_8);
    }

    static void canonicalBase64Url(String value, int start) {
        int length = value.length() - start;
        // 12-byte nonce + 16-byte tag alone require 38 unpadded Base64url characters.
        if (length < 38 || length % 4 == 1) throw new CacheEncryptionException(INVALID_ENVELOPE);
        int last = 0;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 'A' && c <= 'Z') last = c - 'A';
            else if (c >= 'a' && c <= 'z') last = c - 'a' + 26;
            else if (c >= '0' && c <= '9') last = c - '0' + 52;
            else if (c == '-') last = 62;
            else if (c == '_') last = 63;
            else throw new CacheEncryptionException(INVALID_ENVELOPE);
        }
        if ((length % 4 == 2 && (last & 15) != 0) || (length % 4 == 3 && (last & 3) != 0)) {
            throw new CacheEncryptionException(INVALID_ENVELOPE);
        }
    }
}

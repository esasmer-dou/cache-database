package com.reactor.cachedb.core.crypto;

import static com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/** Bounded helpers used by generated code, not entity reflection or ambient provider state. */
public final class EncryptedCodecSupport {
    public static final int MAX_ENCRYPTED_COLUMNS = 32;
    public static final int MAX_ENTITY_COLUMNS = 128;
    public static final String QUEUE_FORMAT = "$cachedb:crypto:format";
    public static final String QUEUE_TENANT = "$cachedb:crypto:tenant";
    public static final String QUEUE_VERSION = "~Q1";
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private EncryptedCodecSupport() {}

    public static JcaFieldEncryption bind(CacheKeyProvider provider, Set<String> rings) {
        if (provider == null || rings == null || rings.isEmpty() || rings.size() > MAX_ENCRYPTED_COLUMNS) {
            throw new CacheEncryptionException(INVALID_CONFIGURATION);
        }
        for (String ring : rings) {
            CryptoSupport.name(ring);
            try {
                KeyMaterial current = provider.current(ring);
                if (current == null) throw new CacheEncryptionException(PROVIDER_FAILURE);
                Optional<KeyMaterial> readable = provider.byId(ring, current.keyId());
                if (readable == null || readable.isEmpty() || !current.keyId().equals(readable.get().keyId())
                        || !current.sameEncryptionKey(readable.get()) || !current.sameBlindIndexKey(readable.get())) {
                    throw new CacheEncryptionException(PROVIDER_FAILURE);
                }
            } catch (RuntimeException invalid) {
                throw new CacheEncryptionException(PROVIDER_FAILURE);
            }
        }
        return new JcaFieldEncryption(provider);
    }

    public static String rowId(String serializedId) {
        CryptoSupport.component(serializedId, JcaFieldEncryption.MAX_ID_BYTES);
        return serializedId;
    }

    /** Tenant identity comes from the row, never from ThreadLocal or a mutable global binding. */
    public static String tenantNamespace(String base, String tenant) {
        CryptoSupport.component(base, JcaFieldEncryption.MAX_NAMESPACE_BYTES);
        if (tenant == null || tenant.isEmpty()) throw new CacheEncryptionException(INVALID_CONTEXT);
        try { CryptoSupport.textLength(tenant, 512); }
        catch (CacheEncryptionException invalid) { throw new CacheEncryptionException(INVALID_CONTEXT); }
        String namespace = base + ":tenant:" + BASE64URL.encodeToString(tenant.getBytes(StandardCharsets.UTF_8));
        CryptoSupport.component(namespace, JcaFieldEncryption.MAX_NAMESPACE_BYTES);
        return namespace;
    }

    public static String sourceEnvelope(Object raw) {
        if (raw == null || raw instanceof String) return (String) raw;
        // Numeric columns, byte arrays and JDBC Clob handles are not compatible text schemas.
        throw new CacheEncryptionException(INVALID_CONFIGURATION);
    }

    public static CacheEncryptionException unbound() { return new CacheEncryptionException(INVALID_CONFIGURATION); }
    public static String queuedTenant(String marker) {
        if (marker == null || !marker.startsWith("~T1:") || marker.length() > 516) {
            throw new CacheEncryptionException(INVALID_CONTEXT);
        }
        String tenant = marker.substring(4);
        if (tenant.isEmpty()) throw new CacheEncryptionException(INVALID_CONTEXT);
        try { CryptoSupport.textLength(tenant, 512); }
        catch (CacheEncryptionException invalid) { throw new CacheEncryptionException(INVALID_CONTEXT); }
        return tenant;
    }

    public static CacheEncryptionException invalidValue() { return new CacheEncryptionException(INVALID_TEXT); }
}

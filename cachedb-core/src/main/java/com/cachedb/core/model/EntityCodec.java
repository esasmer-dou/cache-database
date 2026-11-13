package com.reactor.cachedb.core.model;

import java.util.Map;

public interface EntityCodec<T> {
    String toRedisValue(T entity);
    T fromRedisValue(String encoded);
    Map<String, Object> toColumns(T entity);

    /** Return a new per-runtime codec for encrypted bindings. Legacy codecs remain unchanged. */
    default EntityCodec<T> bindEncryption(com.reactor.cachedb.core.crypto.CacheKeyProvider provider) {
        return this;
    }

    /** Protected metadata requires true before acceptance; legacy/custom defaults cannot claim binding. */
    default boolean encryptionBound() { return false; }

    /** True for encrypted mappings even when their generated static codec is not bound yet. */
    default boolean encryptedMapping() { return encryptionBound(); }

    /** Index-only representation; SQL columns must not be reused for protected index metadata. */
    default Map<String, Object> toIndexColumns(T entity) { return toColumns(entity); }

    /** Transform one typed EQ literal, exactly once. Other operators need metadata validation. */
    default Object indexQueryValue(String column, Object value) { return value; }

    /** Canonical selected logical values, authenticated with a key separate from AES. No ciphertext inputs. */
    default String encryptionIntentToken(T entity, java.util.Set<String> columns) {
        throw new com.reactor.cachedb.core.crypto.CacheEncryptionException(
                com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.INTENT_KEY_UNAVAILABLE);
    }

    /** Domain-separated keyed fingerprint for one complete ordered atomic business intent. */
    default String encryptionIntentFingerprint(java.util.List<String> parts) {
        throw new com.reactor.cachedb.core.crypto.CacheEncryptionException(
                com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.INTENT_KEY_UNAVAILABLE);
    }

    /** SQL persistence values, distinct from logical/business toColumns. */
    default Map<String, Object> toSourceColumns(T entity) { return toColumns(entity); }

    /** Hydrate logical patch/policy values, never a source-storage fallback. */
    default T fromLogicalColumns(Map<String, Object> columns) { return fromColumns(columns); }

    /** Redis-persisted write-behind values, before stream/compaction/DLQ serialization. */
    default Map<String, Object> toQueuedColumns(T entity) { return toColumns(entity); }

    /** Resolved partial assignments against the complete accepted entity state. */
    default Map<String, Object> toQueuedColumns(T entity, Map<String, Object> assignments) {
        return new java.util.LinkedHashMap<>(assignments);
    }

    /** Assignment-only Redis command. Caller supplies canonical ID and an already verified tenant, not an entity. */
    default Map<String, Object> toQueuedPatchColumns(String id, String verifiedTenant, Map<String, Object> assignments) {
        if (encryptedMapping()) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.unbound();
        return new java.util.LinkedHashMap<>(assignments);
    }

    /** Transport NULL/PATCH framing must already be decoded. Call before JDBC fence/DML. */
    default Map<String, String> sourceColumnsFromQueued(String id, Map<String, String> queued) {
        return new java.util.LinkedHashMap<>(queued);
    }

    default boolean directColumnAccess() {
        return false;
    }

    default Map<String, Object> normalizePatchColumns(Map<String, Object> updates) {
        return new java.util.LinkedHashMap<>(updates);
    }

    /** Generated codecs override this without materializing an entity-wide map. */
    default Object entityColumnValue(T entity, String columnName) {
        return columnValue(toColumns(entity), columnName);
    }

    default T fromColumns(Map<String, Object> columns) {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not support JDBC row hydration. "
                        + "Use a generated CacheBinding codec or implement EntityCodec.fromColumns(Map<String, Object>)."
        );
    }

    default Object columnValue(Map<String, Object> columns, String columnName) {
        if (columns == null || columnName == null) {
            return null;
        }
        if (columns.containsKey(columnName)) {
            return columns.get(columnName);
        }
        for (Map.Entry<String, Object> entry : columns.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }
        return null;
    }
}

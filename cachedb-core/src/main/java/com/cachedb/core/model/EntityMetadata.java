package com.reactor.cachedb.core.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public interface EntityMetadata<T, ID> {
    String entityName();
    String tableName();
    String redisNamespace();
    String idColumn();
    default String versionColumn() {
        return "entity_version";
    }

    default String deletedColumn() {
        return null;
    }

    default String activeMarkerValue() {
        return "false";
    }

    default String deletedMarkerValue() {
        return "true";
    }

    Class<T> entityType();
    Function<T, ID> idAccessor();
    List<String> columns();
    /** True only for generated records whose complete state is known immutable. */
    default boolean deeplyImmutable() { return false; }
    default int schemaVersion() { return 1; }
    /** Generated true only for explicitly opted-in, indexed-tenant protection bindings. */
    default boolean tenantPurgeEnabled() { return false; }
    /** Empty preserves existing generated metadata; no reflection-based annotation discovery. */
    default java.util.Optional<com.reactor.cachedb.core.retention.RetentionPolicy> retentionPolicy() {
        return java.util.Optional.empty();
    }
    default String tenantFieldName() { return null; }
    default String extractTenantId(T entity) { return null; }
    default int compressionThresholdBytes() { return 0; }
    default com.reactor.cachedb.core.codec.CachePayloadUpcaster payloadUpcaster() {
        return com.reactor.cachedb.core.codec.NoOpPayloadUpcaster.INSTANCE;
    }
    default com.reactor.cachedb.core.cache.NearCachePolicy nearCachePolicy() {
        return com.reactor.cachedb.core.cache.NearCachePolicy.disabled();
    }
    /** Legacy metadata continues indexing every persisted column. */
    default java.util.Set<String> indexedColumns() {
        return java.util.Set.copyOf(columns());
    }
    default Map<String, String> columnTypes() {
        return Map.of();
    }
    /** Physical column -> immutable protection contract; old metadata has no encrypted columns. */
    default Map<String, com.reactor.cachedb.core.crypto.EncryptedColumnDescriptor> encryptedColumns() {
        return Map.of();
    }
    default Map<String, List<String>> compositeExactIndexes() { return Map.of(); }
    /** Opt-in composite name -> persisted score column; legacy bindings remain unchanged. */
    default Map<String, String> compositeSortColumns() { return Map.of(); }
    /** Explicit schema/business contract: distinct IDs have no cross-row ordering constraints. */
    default boolean independentWriteReordering() { return false; }
    default Map<String, List<String>> partitionedSortIndexes() {
        return Map.of();
    }
    List<RelationDefinition> relations();
}

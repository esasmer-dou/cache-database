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
    default Map<String, List<String>> compositeExactIndexes() { return Map.of(); }
    /** Explicit schema/business contract: distinct IDs have no cross-row ordering constraints. */
    default boolean independentWriteReordering() { return false; }
    default Map<String, List<String>> partitionedSortIndexes() {
        return Map.of();
    }
    List<RelationDefinition> relations();
}

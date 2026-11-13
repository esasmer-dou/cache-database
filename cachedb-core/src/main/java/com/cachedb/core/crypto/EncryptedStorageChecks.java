package com.reactor.cachedb.core.crypto;

import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.query.*;
import java.util.Map;
import java.util.Objects;

/** Explicit, bounded storage contracts; no entity reflection or ambient provider lookup. */
public final class EncryptedStorageChecks {
    public static final long MAX_PREPARED_WRITE_BYTES = 8L * 1024 * 1024;
    private EncryptedStorageChecks() {}

    public static boolean protectedEntity(EntityMetadata<?, ?> metadata) {
        return metadata != null && !metadata.encryptedColumns().isEmpty();
    }

    public static <T> EntityCodec<T> bind(EntityMetadata<T, ?> metadata, EntityCodec<T> codec, CacheKeyProvider provider) {
        if (!protectedEntity(metadata)) return codec;
        try {
            if (codec == null) throw EncryptedCodecSupport.unbound();
            validateMetadata(metadata);
            if (!codec.encryptionBound()) {
                if (provider == null) throw EncryptedCodecSupport.unbound();
                codec = codec.bindEncryption(provider);
            }
            requireBound(metadata, codec);
            return codec;
        } catch (CacheEncryptionException safe) { throw new CacheEncryptionException(safe.code()); }
        catch (RuntimeException unsafe) { throw EncryptedCodecSupport.unbound(); }
    }

    public static void requireBound(EntityMetadata<?, ?> metadata, EntityCodec<?> codec) {
        if (protectedEntity(metadata) && (codec == null || !codec.encryptionBound())) throw EncryptedCodecSupport.unbound();
    }

    public static void sameMapping(EntityMetadata<?, ?> requested, EntityMetadata<?, ?> actual) {
        if (!Objects.equals(requested.entityType(), actual.entityType())
                || !Objects.equals(requested.tableName(), actual.tableName())
                || !Objects.equals(requested.redisNamespace(), actual.redisNamespace())
                || !Objects.equals(requested.idColumn(), actual.idColumn())
                || !Objects.equals(requested.versionColumn(), actual.versionColumn())
                || !Objects.equals(requested.deletedColumn(), actual.deletedColumn())
                || !Objects.equals(requested.tenantFieldName(), actual.tenantFieldName())
                || !Objects.equals(requested.columns(), actual.columns())
                || !Objects.equals(requested.encryptedColumns(), actual.encryptedColumns()))
            throw EncryptedCodecSupport.unbound();
    }

    public static void validateMetadata(EntityMetadata<?, ?> metadata) {
        if (!protectedEntity(metadata)) return;
        var physicalColumns = new java.util.HashSet<>(metadata.columns());
        if (metadata.versionColumn() != null && !metadata.versionColumn().isBlank()) physicalColumns.add(metadata.versionColumn());
        if (metadata.deletedColumn() != null && !metadata.deletedColumn().isBlank()) physicalColumns.add(metadata.deletedColumn());
        if (physicalColumns.size() > EncryptedCodecSupport.MAX_ENTITY_COLUMNS
                || metadata.encryptedColumns().size() > EncryptedCodecSupport.MAX_ENCRYPTED_COLUMNS)
            throw EncryptedCodecSupport.unbound();
        for (var entry : metadata.encryptedColumns().entrySet()) {
            String column = entry.getKey();
            var descriptor = entry.getValue();
            if (column == null || descriptor == null || !metadata.columns().contains(column)
                    || column.equals(metadata.idColumn()) || column.equals(metadata.versionColumn())
                    || column.equals(metadata.deletedColumn()) || column.equals(metadata.tenantFieldName()))
                throw EncryptedCodecSupport.unbound();
            if (!descriptor.blindIndex() && metadata.indexedColumns().contains(column))
                throw EncryptedCodecSupport.unbound();
            if (metadata.compositeExactIndexes().values().stream().anyMatch(columns -> columns.contains(column))
                    || metadata.compositeSortColumns().containsValue(column)
                    || metadata.partitionedSortIndexes().containsKey(column)
                    || metadata.partitionedSortIndexes().values().stream().anyMatch(columns -> columns.contains(column)))
                throw EncryptedCodecSupport.unbound();
        }
    }

    public static EncryptedColumnDescriptor descriptor(EntityMetadata<?, ?> metadata, String column) {
        if (column == null) return null;
        if (protectedEntity(metadata) && !column.isBlank() && !column.equals("*") && !metadata.columns().contains(column))
            throw EncryptedCodecSupport.invalidValue();
        var exact = metadata.encryptedColumns().get(column);
        if (exact != null) return exact;
        for (String protectedColumn : metadata.encryptedColumns().keySet())
            if (protectedColumn.equalsIgnoreCase(column)) throw EncryptedCodecSupport.invalidValue();
        return null;
    }

    public static void rejectProtectedRole(EntityMetadata<?, ?> metadata, String column) {
        if (descriptor(metadata, column) != null) throw EncryptedCodecSupport.invalidValue();
    }

    public static void validateFilter(EntityMetadata<?, ?> metadata, QueryFilter filter) {
        if (!protectedEntity(metadata)) return;
        if (filter == null || filter.column() == null) throw EncryptedCodecSupport.invalidValue();
        if (!metadata.columns().contains(filter.column())) throw EncryptedCodecSupport.invalidValue();
        var descriptor = descriptor(metadata, filter.column());
        if (descriptor != null && (!descriptor.blindIndex() || filter.operator() != QueryOperator.EQ
                || filter.values() != null && !filter.values().isEmpty())) throw EncryptedCodecSupport.invalidValue();
    }

    public static void validateQuery(EntityMetadata<?, ?> metadata, QuerySpec query) {
        if (query == null || !protectedEntity(metadata)) return;
        if (query.filters().size() > 4096 || query.sorts().size() > 128) throw EncryptedCodecSupport.invalidValue();
        for (var filter : query.filters()) validateFilter(metadata, filter);
        for (var sort : query.sorts()) rejectProtectedRole(metadata, sort.column());
    }

    /** Only a logical REDIS_ONLY predicate has a compatible ordinary SQL representation. */
    public static void validateSourceQuery(EntityMetadata<?, ?> metadata, QuerySpec query) {
        validateQuery(metadata, query);
        if (query == null || !protectedEntity(metadata)) return;
        for (var filter : query.filters()) {
            var descriptor = descriptor(metadata, filter.column());
            if (descriptor != null && descriptor.encryptsSource())
                throw new CacheEncryptionException(CacheEncryptionException.Code.UNSUPPORTED_SOURCE_OPERATION);
        }
    }

    public static Object indexLiteral(EntityMetadata<?, ?> metadata, EntityCodec<?> codec, String column, Object value) {
        requireBound(metadata, codec);
        var descriptor = descriptor(metadata, column);
        if (descriptor == null) return value;
        if (!descriptor.blindIndex()) throw EncryptedCodecSupport.invalidValue();
        try {
            Object indexed = codec.indexQueryValue(column, value);
            if (value == null ? indexed != null : !(indexed instanceof String text) || !blindToken(text))
                throw EncryptedCodecSupport.unbound();
            return indexed;
        } catch (CacheEncryptionException safe) { throw new CacheEncryptionException(safe.code()); }
        catch (RuntimeException unsafe) { throw EncryptedCodecSupport.invalidValue(); }
    }

    public static void validateIndexColumns(EntityMetadata<?, ?> metadata, Map<String, ?> columns) {
        if (!protectedEntity(metadata)) return;
        if (columns == null || columns.size() > 130) throw EncryptedCodecSupport.invalidValue();
        for (var entry : metadata.encryptedColumns().entrySet()) {
            if (!columns.containsKey(entry.getKey())) continue;
            if (!entry.getValue().blindIndex()) throw EncryptedCodecSupport.invalidValue();
            Object value = columns.get(entry.getKey());
            if (value == null || "__NULL__".equals(value)) continue;
            if (!(value instanceof String text) || !blindToken(text)) throw EncryptedCodecSupport.invalidValue();
        }
    }

    private static boolean blindToken(String text) {
        if (text.length() != 47 || !text.startsWith("~B1:")) return false;
        for (int i = 4; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_')) return false;
        }
        return "AEIMQUYcgkosw048".indexOf(text.charAt(46)) >= 0;
    }

    public static void validateQueuedColumns(EntityMetadata<?, ?> metadata, Map<String, ?> columns) {
        if (!protectedEntity(metadata)) return;
        if (columns == null || columns.size() > 130
                || !EncryptedCodecSupport.QUEUE_VERSION.equals(columns.get(EncryptedCodecSupport.QUEUE_FORMAT)))
            throw EncryptedCodecSupport.invalidValue();
        if (metadata.tenantFieldName() != null) {
            Object tenant = columns.get(EncryptedCodecSupport.QUEUE_TENANT);
            if (!(tenant instanceof String text)) throw EncryptedCodecSupport.invalidValue();
            EncryptedCodecSupport.queuedTenant(text);
        }
        for (String column : metadata.encryptedColumns().keySet()) {
            Object value = columns.get(column);
            if (value == null) continue;
            if (!(value instanceof String text) || text.length() > JcaFieldEncryption.MAX_FIELD_BYTES
                    || !text.startsWith("~E1:")) throw EncryptedCodecSupport.invalidValue();
        }
    }
    /** A retained prepared chunk, not a process-wide concurrency budget. */
    public static long preparedBytes(long previous, String payload, Map<String, ?> columns) {
        if (previous < 0 || previous > MAX_PREPARED_WRITE_BYTES || columns == null || columns.size() > 130)
            throw EncryptedCodecSupport.invalidValue();
        long bytes = previous + utf8Bytes(payload);
        for (var entry : columns.entrySet()) {
            bytes += utf8Bytes(entry.getKey());
            Object value = entry.getValue();
            if (value != null) {
                try { bytes += utf8Bytes(value instanceof String text ? text : value.toString()); }
                catch (RuntimeException unsafe) { throw EncryptedCodecSupport.invalidValue(); }
            }
            if (bytes > MAX_PREPARED_WRITE_BYTES) throw EncryptedCodecSupport.invalidValue();
        }
        if (bytes > MAX_PREPARED_WRITE_BYTES) throw EncryptedCodecSupport.invalidValue();
        return bytes;
    }

    private static long utf8Bytes(String value) {
        if (value == null) return 0;
        if (value.length() > MAX_PREPARED_WRITE_BYTES) throw EncryptedCodecSupport.invalidValue();
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) { bytes += 4; i++; }
            else bytes += 3;
            if (bytes > MAX_PREPARED_WRITE_BYTES) throw EncryptedCodecSupport.invalidValue();
        }
        return bytes;
    }

}

package com.reactor.cachedb.core.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Read-only table mapping, independent of Spring and CRUD registration. */
public record SourceMapping<T>(
        String table, List<String> columns, Function<Map<String, Object>, T> decoder,
        EntityMetadata<T, ?> entityMetadata) {
    public SourceMapping(String table, List<String> columns, Function<Map<String, Object>, T> decoder) {
        this(table, columns, decoder, null);
    }
    public SourceMapping {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(decoder, "decoder");
        columns = List.copyOf(columns);
        if (columns.isEmpty() || columns.stream().distinct().count() != columns.size())
            throw new IllegalArgumentException("Source columns must be nonempty and unique");
    }

    public SourceMapping<T> bindEncryption(com.reactor.cachedb.core.crypto.CacheKeyProvider provider) {
        if (!com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(entityMetadata)) return this;
        if (!(decoder instanceof EntityDecoder<?> captured)) throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.unbound();
        @SuppressWarnings("unchecked") EntityCodec<T> codec = (EntityCodec<T>) captured.codec;
        return withCodec(com.reactor.cachedb.core.crypto.EncryptedStorageChecks.bind(entityMetadata, codec, provider));
    }

    public SourceMapping<T> withCodec(EntityCodec<T> codec) {
        if (entityMetadata == null) throw new IllegalStateException("Source mapping is not an entity mapping");
        if (com.reactor.cachedb.core.crypto.EncryptedStorageChecks.protectedEntity(entityMetadata)
                && (!Objects.equals(table, entityMetadata.tableName()) || !Objects.equals(columns, entityMetadata.columns())))
            throw com.reactor.cachedb.core.crypto.EncryptedCodecSupport.unbound();
        com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(entityMetadata, codec);
        return new SourceMapping<>(table, columns, new EntityDecoder<>(entityMetadata, codec), entityMetadata);
    }

    private static final class EntityDecoder<T> implements Function<Map<String, Object>, T> {
        private final EntityMetadata<T, ?> metadata;
        private final EntityCodec<T> codec;
        private EntityDecoder(EntityMetadata<T, ?> metadata, EntityCodec<T> codec) { this.metadata = metadata; this.codec = codec; }
        public T apply(Map<String, Object> row) {
            com.reactor.cachedb.core.crypto.EncryptedStorageChecks.requireBound(metadata, codec);
            return codec.fromColumns(row);
        }
    }

    public static <T, ID> SourceMapping<T> entity(
            EntityMetadata<T, ID> metadata, EntityCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        return new SourceMapping<>(metadata.tableName(), metadata.columns(), new EntityDecoder<>(metadata, codec), metadata);
    }
}

package com.reactor.cachedb.core.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Read-only table mapping, independent of Spring and CRUD registration. */
public record SourceMapping<T>(
        String table, List<String> columns, Function<Map<String, Object>, T> decoder) {
    public SourceMapping {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(decoder, "decoder");
        columns = List.copyOf(columns);
        if (columns.isEmpty() || columns.stream().distinct().count() != columns.size())
            throw new IllegalArgumentException("Source columns must be nonempty and unique");
    }

    public static <T, ID> SourceMapping<T> entity(
            EntityMetadata<T, ID> metadata, EntityCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        return new SourceMapping<>(metadata.tableName(), metadata.columns(), codec::fromColumns);
    }
}

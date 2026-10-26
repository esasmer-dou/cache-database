package com.reactor.cachedb.core.query;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Compile-time column identity. Query evaluation retains the existing scalar semantics. */
public record TypedColumn<E, T>(Class<E> entityType, String columnName, String fieldName, Class<T> javaType) {
    public TypedColumn {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(javaType, "javaType");
        if (columnName == null || columnName.isBlank() || fieldName == null || fieldName.isBlank())
            throw new IllegalArgumentException("Column and field names must not be blank");
    }

    public QueryFilter eq(T value) { return QueryFilter.eq(columnName, value); }
    public QueryFilter ne(T value) { return QueryFilter.ne(columnName, value); }
    public QueryFilter in(Collection<? extends T> values) { return QueryFilter.in(columnName, values); }
    public QueryFilter gt(T value) { return QueryFilter.gt(columnName, value); }
    public QueryFilter gte(T value) { return QueryFilter.gte(columnName, value); }
    public QueryFilter lt(T value) { return QueryFilter.lt(columnName, value); }
    public QueryFilter lte(T value) { return QueryFilter.lte(columnName, value); }
    public QuerySort asc() { return QuerySort.asc(columnName); }
    public QuerySort desc() { return QuerySort.desc(columnName); }

    /** Explicit parsers remain required for application-specific ID types. */
    public Function<String, T> parser() {
        if (javaType == String.class) return value -> javaType.cast(value);
        if (javaType == Long.class) return value -> javaType.cast(Long.valueOf(value));
        if (javaType == Integer.class) return value -> javaType.cast(Integer.valueOf(value));
        if (javaType == UUID.class) return value -> javaType.cast(UUID.fromString(value));
        throw new IllegalArgumentException("No implicit root parser for " + javaType.getName());
    }
}

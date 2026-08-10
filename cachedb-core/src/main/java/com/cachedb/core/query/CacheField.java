package com.reactor.cachedb.core.query;

import java.util.Collection;
import java.util.List;

/** Generated, allocation-light metamodel field used instead of raw column strings. */
public record CacheField<E, V>(String javaName, String columnName, Class<?> valueType) {
    public CacheField {
        if (javaName == null || javaName.isBlank()) throw new IllegalArgumentException("javaName must not be blank");
        if (columnName == null || columnName.isBlank()) throw new IllegalArgumentException("columnName must not be blank");
        if (valueType == null) throw new IllegalArgumentException("valueType must not be null");
    }

    public QueryFilter eq(V value) { return QueryFilter.eq(columnName, value); }
    public QueryFilter ne(V value) { return QueryFilter.ne(columnName, value); }
    public QueryFilter gt(V value) { return QueryFilter.gt(columnName, value); }
    public QueryFilter gte(V value) { return QueryFilter.gte(columnName, value); }
    public QueryFilter lt(V value) { return QueryFilter.lt(columnName, value); }
    public QueryFilter lte(V value) { return QueryFilter.lte(columnName, value); }
    public QueryFilter in(Collection<? extends V> values) {
        return QueryFilter.in(columnName, values == null ? List.of() : List.copyOf(values));
    }
    public QueryFilter contains(V value) { return QueryFilter.contains(columnName, value); }
    public QueryFilter startsWith(V value) { return QueryFilter.startsWith(columnName, value); }
    public QuerySort asc() { return QuerySort.asc(columnName); }
    public QuerySort desc() { return QuerySort.desc(columnName); }
}

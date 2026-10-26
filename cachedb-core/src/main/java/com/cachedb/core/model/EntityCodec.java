package com.reactor.cachedb.core.model;

import java.util.Map;

public interface EntityCodec<T> {
    String toRedisValue(T entity);
    T fromRedisValue(String encoded);
    Map<String, Object> toColumns(T entity);

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

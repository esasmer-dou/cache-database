package com.reactor.cachedb.core.projection;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ProjectionRow {

    private final Map<String, String> values;

    ProjectionRow(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public <V> V value(String column, ProjectionValueCodec<V> codec) {
        if (!values.containsKey(column)) {
            throw new IllegalArgumentException("Projection column is not present: " + column);
        }
        return codec.decode(values.get(column));
    }

    public String string(String column) {
        return value(column, ProjectionValueCodecs.STRING);
    }

    public Long longValue(String column) {
        return value(column, ProjectionValueCodecs.LONG);
    }

    public Integer integer(String column) {
        return value(column, ProjectionValueCodecs.INTEGER);
    }

    public Double doubleValue(String column) {
        return value(column, ProjectionValueCodecs.DOUBLE);
    }

    public Boolean booleanValue(String column) {
        return value(column, ProjectionValueCodecs.BOOLEAN);
    }

    public BigDecimal decimal(String column) {
        return value(column, ProjectionValueCodecs.BIG_DECIMAL);
    }

    public UUID uuid(String column) {
        return value(column, ProjectionValueCodecs.UUID_VALUE);
    }
}

package com.reactor.cachedb.core.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ProjectionRow {

    private final Map<String, String> values;
    private final Map<String, Integer> positions;
    private final String[] fields;
    private final boolean[] present;
    private final String[] extras;
    private final int extraSize;

    ProjectionRow(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        positions = null;
        fields = null;
        present = null;
        extras = null;
        extraSize = 0;
    }

    ProjectionRow(Map<String, Integer> positions, String[] fields, boolean[] present) {
        this(positions,fields,present,null,0);
    }

    ProjectionRow(Map<String,Integer> positions,String[] fields,boolean[] present,String[] extras,int extraSize) {
        this.values = null;
        this.positions = positions;
        this.fields = fields;
        this.present = present;
        this.extras = extras;
        this.extraSize = extraSize;
    }

    public <V> V value(String column, ProjectionValueCodec<V> codec) {
        if (positions != null) {
            Integer index = column == null ? null : positions.get(column);
            if (index != null && present[index]) return codec.decode(fields[index]);
            // Rare legacy/forward fields retain last-value-wins without a per-row map.
            for (int i=extraSize-2; i>=0; i-=2) {
                if (java.util.Objects.equals(column,extras[i])) return codec.decode(extras[i+1]);
            }
            throw new IllegalArgumentException("Projection column is not present: " + column);
        }
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

    public Short shortValue(String column) {
        return value(column, ProjectionValueCodecs.SHORT);
    }

    public Double doubleValue(String column) {
        return value(column, ProjectionValueCodecs.DOUBLE);
    }

    public Float floatValue(String column) {
        return value(column, ProjectionValueCodecs.FLOAT);
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

    public Instant instant(String column) {
        return value(column, ProjectionValueCodecs.INSTANT);
    }

    public LocalDate localDate(String column) {
        return value(column, ProjectionValueCodecs.LOCAL_DATE);
    }

    public LocalDateTime localDateTime(String column) {
        return value(column, ProjectionValueCodecs.LOCAL_DATE_TIME);
    }

    public LocalTime localTime(String column) {
        return value(column, ProjectionValueCodecs.LOCAL_TIME);
    }
}

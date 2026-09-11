package com.reactor.cachedb.core.model;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** Scalar JDBC conversions used by compile-time source record bindings. No reflection. */
public final class SourceValues {
    private SourceValues() {}

    public static <V> V read(Map<String, Object> row, String column, Class<V> type) {
        if (!row.containsKey(column))
            throw new IllegalArgumentException("Missing source column: " + column);
        Object value = row.get(column);
        if (value == null) return null;
        if (type.isInstance(value)) return type.cast(value);
        Object converted;
        if (type == String.class) converted = value.toString();
        else if (type == UUID.class) converted = UUID.fromString(value.toString());
        else if (type == Boolean.class && value instanceof Number number) {
            int flag = new BigDecimal(number.toString()).intValueExact();
            if (flag != 0 && flag != 1)
                throw new IllegalArgumentException("Expected boolean 0/1: " + column);
            converted = flag == 1;
        } else if (value instanceof Number number && type == Integer.class)
            converted = new BigDecimal(number.toString()).intValueExact();
        else if (value instanceof Number number && type == Long.class)
            converted = new BigDecimal(number.toString()).longValueExact();
        else if (value instanceof Number number && type == Double.class)
            converted = number.doubleValue();
        else if (value instanceof Number number && type == BigDecimal.class)
            converted = new BigDecimal(number.toString());
        else if (value instanceof Timestamp timestamp && type == LocalDateTime.class)
            converted = timestamp.toLocalDateTime();
        else if (value instanceof Timestamp timestamp && type == Instant.class)
            converted = timestamp.toInstant();
        else if (value instanceof java.sql.Date date && type == LocalDate.class)
            converted = date.toLocalDate();
        else
            throw new IllegalArgumentException(
                    "Unsupported source value type for column: " + column);
        return type.cast(converted);
    }
}

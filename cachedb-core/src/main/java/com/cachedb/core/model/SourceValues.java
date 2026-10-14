package com.reactor.cachedb.core.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Scalar JDBC conversions used by compile-time source record bindings. No reflection. */
public final class SourceValues {
    private SourceValues() {}

    public static <V> V read(Map<String, Object> row, String column, Class<V> type) {
        if (!row.containsKey(column))
            throw new IllegalArgumentException("Missing source column: " + column);
        Object value = row.get(column);
        if (value == null) return null;
        if (type.isInstance(value) && type != Float.class) return type.cast(value);
        Object converted;
        if (type == String.class) converted = value.toString();
        else if (type == UUID.class && value instanceof byte[] bytes) {
            if (bytes.length != 16)
                throw new IllegalArgumentException("UUID requires 16 bytes: " + column);
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            converted = new UUID(buffer.getLong(), buffer.getLong());
        } else if (type == UUID.class) converted = UUID.fromString(value.toString());
        else if (type == Boolean.class && value instanceof Number number) {
            int flag = new BigDecimal(number.toString()).intValueExact();
            if (flag != 0 && flag != 1)
                throw new IllegalArgumentException("Expected boolean 0/1: " + column);
            converted = flag == 1;
        } else if (value instanceof Number number && type == Integer.class)
            converted = new BigDecimal(number.toString()).intValueExact();
        else if (value instanceof Number number && type == Long.class)
            converted = new BigDecimal(number.toString()).longValueExact();
        else if (value instanceof Number number && type == Short.class)
            converted = new BigDecimal(number.toString()).shortValueExact();
        else if (value instanceof Number number && type == Byte.class)
            converted = new BigDecimal(number.toString()).byteValueExact();
        else if (value instanceof Number number && type == BigInteger.class)
            converted = new BigDecimal(number.toString()).toBigIntegerExact();
        else if (value instanceof Number number && type == Float.class) {
            float result = number.floatValue();
            if (!Float.isFinite(result)
                    || (result == 0f && new BigDecimal(number.toString()).signum() != 0))
                throw new ArithmeticException("Float overflow or underflow: " + column);
            converted = result;
        } else if (value instanceof Number number && type == Double.class)
            converted = number.doubleValue();
        else if (value instanceof Number number && type == BigDecimal.class)
            converted = new BigDecimal(number.toString());
        else if (value instanceof Timestamp timestamp && type == LocalDateTime.class)
            converted = timestamp.toLocalDateTime();
        else if (value instanceof Timestamp timestamp && type == Instant.class)
            converted = timestamp.toInstant();
        else if (value instanceof Timestamp timestamp && type == OffsetDateTime.class)
            converted = timestamp.toInstant().atOffset(ZoneOffset.UTC);
        else if (value instanceof Instant instant && type == OffsetDateTime.class)
            converted = instant.atOffset(ZoneOffset.UTC);
        else if (value instanceof String text && type == OffsetDateTime.class)
            converted = OffsetDateTime.parse(text);
        else if (value instanceof java.sql.Time time && type == LocalTime.class)
            converted = time.toLocalTime();
        else if (value instanceof Timestamp time && type == LocalTime.class)
            converted = time.toLocalDateTime().toLocalTime();
        else if (value instanceof java.sql.Date date && type == LocalDate.class)
            converted = date.toLocalDate();
        else
            throw new IllegalArgumentException(
                    "Unsupported source value type for column: " + column);
        return type.cast(converted);
    }

    /** The generated resolver performs enum lookup without reflective enum discovery. */
    public static <E> E readEnum(
            Map<String, Object> row, String column, Function<String, E> enumResolver) {
        Objects.requireNonNull(enumResolver, "enumResolver");
        if (!row.containsKey(column))
            throw new IllegalArgumentException("Missing source column: " + column);
        Object value = row.get(column);
        if (value == null) return null;
        return enumResolver.apply(value instanceof Enum<?> e ? e.name() : value.toString());
    }
}

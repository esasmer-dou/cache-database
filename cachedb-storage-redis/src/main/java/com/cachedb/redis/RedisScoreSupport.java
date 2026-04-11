package com.reactor.cachedb.redis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

final class RedisScoreSupport {

    private RedisScoreSupport() {
    }

    static Double toScore(Map<String, String> columnTypes, String column, Object value) {
        if (value == null) {
            return null;
        }

        String declaredType = columnTypes == null ? null : columnTypes.get(column);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Instant instant) {
            return (double) instant.toEpochMilli();
        }
        if (value instanceof LocalDate localDate) {
            return (double) localDate.toEpochDay();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return (double) localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return (double) offsetDateTime.toInstant().toEpochMilli();
        }
        if ("java.time.Instant".equals(declaredType)) {
            return (double) Instant.parse(String.valueOf(value)).toEpochMilli();
        }
        if ("java.time.LocalDate".equals(declaredType)) {
            return (double) LocalDate.parse(String.valueOf(value)).toEpochDay();
        }
        if ("java.time.LocalDateTime".equals(declaredType)) {
            return (double) LocalDateTime.parse(String.valueOf(value)).toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        if ("java.time.OffsetDateTime".equals(declaredType)) {
            return (double) OffsetDateTime.parse(String.valueOf(value)).toInstant().toEpochMilli();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}

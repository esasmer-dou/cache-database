package com.reactor.cachedb.core.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

class SourceValuesTest {
    enum State {
        ACTIVE,
        CLOSED
    }

    private <T> T read(Object value, Class<T> type) {
        return SourceValues.read(Map.of("v", value), "v", type);
    }

    @Test
    void temporalConversionsPreserveInstantAndOffset() {
        Instant instant = Instant.parse("2026-10-14T01:02:03Z");
        assertEquals(
                instant.atOffset(ZoneOffset.UTC),
                read(Timestamp.from(instant), OffsetDateTime.class));
        assertEquals(instant.atOffset(ZoneOffset.UTC), read(instant, OffsetDateTime.class));
        assertEquals(
                OffsetDateTime.parse("2026-10-14T04:02:03+03:00"),
                read("2026-10-14T04:02:03+03:00", OffsetDateTime.class));
        assertEquals(LocalTime.of(12, 34, 56), read(Time.valueOf("12:34:56"), LocalTime.class));
    }

    @Test
    void exactNumericConversionsRejectLoss() {
        assertEquals(
                new BigInteger("123456789012345678901"),
                read(new BigDecimal("123456789012345678901"), BigInteger.class));
        assertEquals((short) 32767, read(new BigDecimal("32767"), Short.class));
        assertEquals((byte) -128, read(-128L, Byte.class));
        assertEquals(1.5f, read(new BigDecimal("1.5"), Float.class));
        assertThrows(
                ArithmeticException.class, () -> read(new BigDecimal("1.1"), BigInteger.class));
        assertThrows(ArithmeticException.class, () -> read(32768, Short.class));
        assertThrows(ArithmeticException.class, () -> read(128, Byte.class));
        assertThrows(ArithmeticException.class, () -> read(Double.MAX_VALUE, Float.class));
        assertThrows(ArithmeticException.class, () -> read(Double.MIN_VALUE, Float.class));
        assertThrows(ArithmeticException.class, () -> read(new BigDecimal("1e-1000"), Float.class));
        assertThrows(ArithmeticException.class, () -> read(Float.NaN, Float.class));
    }

    @Test
    void enumResolverAndNullableColumnsAreExplicit() {
        assertEquals(
                State.ACTIVE, SourceValues.readEnum(Map.of("s", "ACTIVE"), "s", State::valueOf));
        Map<String, Object> row = new HashMap<>();
        row.put("s", null);
        assertNull(SourceValues.readEnum(row, "s", State::valueOf));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceValues.readEnum(Map.of("s", "UNKNOWN"), "s", State::valueOf));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceValues.read(row, "missing", Boolean.class));
    }

    @Test
    void binaryUuidDecodesWithoutReflectionAndRejectsWrongLength() {
        var id = UUID.randomUUID();
        var bytes =
                java.nio.ByteBuffer.allocate(16)
                        .putLong(id.getMostSignificantBits())
                        .putLong(id.getLeastSignificantBits())
                        .array();
        assertEquals(id, read(bytes, UUID.class));
        assertThrows(IllegalArgumentException.class, () -> read(new byte[10], UUID.class));
    }
}

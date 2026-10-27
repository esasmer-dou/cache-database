package com.reactor.cachedb.core.query;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AggregateResultTest {
    @Test void exactDecimalSumAndNullAverageMembership() {
        var accumulator = new AggregateResult.Accumulator();
        accumulator.add(new BigDecimal("9007199254740993.01"));
        accumulator.add(new BigDecimal("-9007199254740993.00"));
        accumulator.add(null);
        var result = accumulator.result();
        assertEquals(3, result.count());
        assertEquals(new BigDecimal("0.01"), result.sum());
        assertEquals(0.005d, result.average().orElseThrow(), 0.000001d);
        assertEquals(new BigDecimal("-9007199254740993.00"), result.min());
        assertEquals(new BigDecimal("9007199254740993.01"), result.max());
    }
    @Test void emptyResultHasNoInventedMinimumOrAverage() {
        var result = new AggregateResult.Accumulator().result();
        assertEquals(0, result.count()); assertEquals(BigDecimal.ZERO, result.sum());
        assertNull(result.min()); assertNull(result.max()); assertTrue(result.average().isEmpty());
    }
    @Test void extremeScaleIsRejectedBeforeAlignmentAllocation() {
        var accumulator = new AggregateResult.Accumulator();
        assertThrows(IllegalArgumentException.class, () -> accumulator.add(new BigDecimal("1E-2147483647")));
    }
}

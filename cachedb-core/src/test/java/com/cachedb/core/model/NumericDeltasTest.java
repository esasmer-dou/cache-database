package com.reactor.cachedb.core.model;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.exception.CacheDeltaBoundsException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class NumericDeltasTest {
    @Test void preservesDecimalPrecisionAndChecksBoundsBeforeAcceptance() {
        var spec = new PatchDeltaSpec("balance", "balance", new BigDecimal("-0.01"), 0, Long.MAX_VALUE);
        assertEquals(new BigDecimal("9007199254740993.00"), NumericDeltas.apply("Wallet", 1L, "java.math.BigDecimal", spec, new BigDecimal("9007199254740993.01")));
        var failure = assertThrows(CacheDeltaBoundsException.class, () -> NumericDeltas.apply("Wallet", 1L, "java.math.BigDecimal", spec, BigDecimal.ZERO));
        assertEquals("balance", failure.columnName()); assertEquals(1L, failure.entityId());
    }
    @Test void neverWrapsIntegerOrLongArithmeticAndRejectsNonfiniteCommands() {
        assertThrows(IllegalArgumentException.class, () -> NumericDeltas.apply("Wallet", 1L, "java.math.BigDecimal",
                new PatchDeltaSpec("balance", "balance", new BigDecimal("1E-2147483647"), 0, 100), BigDecimal.ONE));
        assertThrows(ArithmeticException.class, () -> NumericDeltas.apply("Stock", 1L, "int", new PatchDeltaSpec("stock", "stock", 1, 0, Long.MAX_VALUE), Integer.MAX_VALUE));
        assertThrows(CacheDeltaBoundsException.class, () -> NumericDeltas.apply("Stock", 1L, "long", new PatchDeltaSpec("stock", "stock", 1L, 0, Long.MAX_VALUE), Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new PatchDeltaSpec("stock", "stock", Double.NaN, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> new PatchDeltaSpec("stock", "stock", new java.util.concurrent.atomic.AtomicLong(1), 0, 100));
    }
}

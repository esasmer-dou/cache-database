package com.reactor.cachedb.core.query;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FacetAccumulatorTest {
    @Test void weightedCountsRetainOrderingNullGroupsAndCandidateLimits() {
        var facets = new FacetAccumulator();
        facets.addCount("z", 510);
        facets.addCount("a", 5);
        facets.addCount("z", 2);
        facets.addCount(null, 3);
        assertEquals(List.of("a", "z"), List.copyOf(facets.result().keySet()));
        assertEquals(512, facets.result().get("z").count());
        assertEquals(BigDecimal.ZERO, facets.result().get("z").sum());
        assertTrue(facets.result().get("z").average().isEmpty());
        assertThrows(IllegalStateException.class, () -> facets.addCount("z", 0));
        assertThrows(IllegalStateException.class, () -> facets.addCount("z", -1));
        assertThrows(IllegalStateException.class, () -> facets.addCount("z", Long.MAX_VALUE));
        facets.addCount("z", 99_480);
        assertThrows(IllegalStateException.class, () -> facets.add("z", null, false));
        var groups = new FacetAccumulator();
        for (int i = 0; i < 256; i++) groups.addCount("g" + i, 1);
        assertThrows(IllegalStateException.class, () -> groups.addCount("overflow", 1));
        assertThrows(IllegalArgumentException.class, () -> new FacetAccumulator().addCount("x".repeat(4097), 1));
    }
    @Test void legacyConstructorAndEqualityRetainTheirOriginalContract() {
        var values = new AggregateResult.Accumulator(); values.add(java.math.BigDecimal.ONE); values.add(null); values.add(java.math.BigDecimal.ZERO); values.add(java.math.BigDecimal.ZERO);
        var computed = values.result();
        var legacy = new AggregateResult(computed.count(),computed.sum(),computed.min(),computed.max(),computed.average());
        assertEquals(legacy,computed); assertEquals(legacy.hashCode(),computed.hashCode());
        assertDoesNotThrow(() -> new AggregateResult(0,null,null,null,null));
        assertDoesNotThrow(() -> new AggregateResult(0,null,null,null,java.util.OptionalDouble.of(Double.NaN)));
    }
    @Test void nullGroupsAreSkippedAndDecimalsRemainExact() {
        var facets = new FacetAccumulator();
        facets.add("z", new BigDecimal("999999999999999999.125"), false);
        facets.add("a", BigDecimal.ONE, false);
        facets.add("z", new BigDecimal("0.875"), false);
        facets.add(null, BigDecimal.TEN, false);
        var result = facets.result();
        assertEquals(List.of("a", "z"), List.copyOf(result.keySet()));
        assertEquals(new BigDecimal("1000000000000000000.000"), result.get("z").sum());
        assertEquals(new BigDecimal("500000000000000000.000"), result.get("z").decimalAverage());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }
    @Test void groupCardinalityAndKeyBytesAreBoundedAndCountFieldSkipsNulls() {
        var facets = new FacetAccumulator();
        for (int i = 0; i < 256; i++) facets.add("g" + i, null, true);
        assertEquals(0, facets.result().get("g0").count());
        facets.add("g0", "__NULL__", true);
        assertEquals(1, facets.result().get("g0").count());
        assertThrows(IllegalStateException.class, () -> facets.add("overflow", null, false));
        assertThrows(IllegalArgumentException.class, () -> facets.add("x".repeat(4097), null, false));
    }
}

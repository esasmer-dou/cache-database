package com.reactor.cachedb.core.cache;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

class CachePolicyLimitTest {
    @Test
    void zeroNeverAdmitsAndMinusOneDoes() {
        var disabled = CachePolicy.builder().hotEntityLimit(CachePolicy.DISABLED).build();
        for (var source : CacheAdmissionSource.values()) {
            assertFalse(disabled.shouldAdmit(Map.of(), source));
            assertFalse(disabled.hotPolicy().shouldAdmit(Map.of(), source));
        }
        assertTrue(
                CachePolicy.builder()
                        .hotEntityLimit(CachePolicy.UNBOUNDED)
                        .build()
                        .shouldAdmit(Map.of(), CacheAdmissionSource.WARM));
        assertEquals(1000, CachePolicy.defaults().hotEntityLimit());
        assertThrows(
                IllegalArgumentException.class,
                () -> CachePolicy.builder().hotEntityLimit(-2).build());
    }
}

package com.reactor.cachedb.core.readmodel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CountFacetValuesTest {
    @Test void acceptsEmptyUngroupedAndPartiallyGroupedCounts() {
        assertEquals(Map.of(), new CountFacetValues(0, Map.of()).groups());
        assertEquals(Long.MAX_VALUE, new CountFacetValues(Long.MAX_VALUE, Map.of()).count());
        assertEquals(Map.of("a", 2L), new CountFacetValues(3, Map.of("a", 2L)).groups());
        assertThrows(IllegalArgumentException.class, () -> new CountFacetValues(-1, Map.of()));
    }

    @Test void preservesEmptyLiteralSentinelAndUtf16GroupKeys() {
        var groups = new LinkedHashMap<String, Long>();
        groups.put("", 1L);
        groups.put("__NULL__", 1L);
        groups.put("\uD83D\uDE00".repeat(2048), 1L);
        assertEquals(groups, new CountFacetValues(3, groups).groups());
        assertThrows(IllegalArgumentException.class, () -> new CountFacetValues(1, Map.of("x".repeat(4097), 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> new CountFacetValues(1, Map.of("\uD83D\uDE00".repeat(2048) + "x", 1L)));
    }

    @Test void rejectsIsolatedSurrogatesButAcceptsWellFormedPairs() {
        for (String key : new String[] {"\uD800", "\uDC00", "a\uD800b", "a\uDC00b", "\uD800\uD800",
                "\uDC00\uDC00", "\uDC00\uD800", "\uD800x\uDC00", "\uD83D\uDE00\uD800", "a".repeat(4095) + "\uD800"})
            assertThrows(IllegalArgumentException.class, () -> new CountFacetValues(1, Map.of(key, 1L)));
        for (String key : new String[] {"\uD800\uDC00", "\uDBFF\uDFFF", "prefix\uD83D\uDE00suffix"})
            assertEquals(Map.of(key, 1L), new CountFacetValues(1, Map.of(key, 1L)).groups());
    }

    @Test void copiesInputAndPreservesEncounterOrderWithoutMutableViews() {
        var input = new LinkedHashMap<String, Long>();
        input.put("z", 2L);
        input.put("a", 1L);
        var values = new CountFacetValues(3, input);
        input.clear();
        assertEquals(List.of("z", "a"), List.copyOf(values.groups().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> values.groups().put("b", 1L));
        assertThrows(UnsupportedOperationException.class, () -> values.groups().clear());
        assertThrows(UnsupportedOperationException.class, () -> values.groups().entrySet().iterator().next().setValue(9L));
    }

    @Test void limitsGroupCardinalityTo256() {
        var groups = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 256; i++) groups.put("g" + i, 1L);
        assertEquals(256, new CountFacetValues(256, groups).groups().size());
        groups.put("overflow", 1L);
        assertThrows(IllegalArgumentException.class, () -> new CountFacetValues(257, groups));
    }

    @Test void rejectsNullMapsKeysAndWeights() {
        assertThrows(NullPointerException.class, () -> new CountFacetValues(0, null));
        var groups = new LinkedHashMap<String, Long>();
        groups.put(null, 1L);
        assertThrows(NullPointerException.class, () -> new CountFacetValues(1, groups));
        groups.clear();
        groups.put("a", null);
        assertThrows(NullPointerException.class, () -> new CountFacetValues(1, groups));
    }

    @Test void rejectsNonpositiveWeightsAndSumsAboveCountWithoutOverflow() {
        for (long weight : new long[] {0, -1, Long.MIN_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new CountFacetValues(1, Map.of("a", weight)));
        assertThrows(IllegalArgumentException.class, () -> new CountFacetValues(0, Map.of("a", 1L)));
        assertThrows(IllegalArgumentException.class, () -> new CountFacetValues(2, Map.of("a", 2L, "b", 1L)));
        var groups = new LinkedHashMap<String, Long>();
        groups.put("a", Long.MAX_VALUE);
        groups.put("b", 1L);
        assertThrows(IllegalArgumentException.class, () -> new CountFacetValues(Long.MAX_VALUE, groups));
        groups.put("a", Long.MAX_VALUE - 1);
        assertEquals(groups, new CountFacetValues(Long.MAX_VALUE, groups).groups());
    }
}

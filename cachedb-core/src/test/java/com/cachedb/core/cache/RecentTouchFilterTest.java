package com.reactor.cachedb.core.cache;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RecentTouchFilterTest {
    @Test void suppressionIsBoundedCollisionSafeAndCanBeDisabled() {
        var filter = new RecentTouchFilter(1);
        assertTrue(filter.acquire("a", 1000));
        assertFalse(filter.acquire("a", 1000));
        assertTrue(filter.acquire("b", 1000));
        assertTrue(filter.acquire("a", 1000));
        filter.forget("a");
        assertTrue(filter.acquire("a", 1000));
        assertTrue(filter.acquire("a", 0));
        assertTrue(filter.acquire("a", 0));
        assertThrows(IllegalArgumentException.class, () -> new RecentTouchFilter(65_537));
        assertEquals(0, CachePolicy.builder().accessDebounceMillis(0).build().accessDebounceMillis());
    }
}

package com.reactor.cachedb.spring.boot.test;

import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.repository.HotLookupStatus;
import com.reactor.cachedb.core.repository.HotWindow;
import com.reactor.cachedb.core.route.RouteCoverage;

public final class CacheDbAssertions {
    private CacheDbAssertions() {
    }

    public static <T> T requireHotHit(HotLookup<T> lookup) {
        if (lookup == null || lookup.status() != HotLookupStatus.HIT || lookup.value() == null) {
            throw new AssertionError("Expected Redis hot hit but was " + (lookup == null ? "null" : lookup.status()));
        }
        return lookup.value();
    }

    public static void requireNotCached(HotLookup<?> lookup) {
        requireStatus(lookup, HotLookupStatus.NOT_CACHED);
    }

    public static void requireTombstoned(HotLookup<?> lookup) {
        requireStatus(lookup, HotLookupStatus.TOMBSTONED);
    }

    public static void requireOutsideHotPolicy(HotLookup<?> lookup) {
        requireStatus(lookup, HotLookupStatus.OUTSIDE_HOT_POLICY);
    }

    public static <T> HotWindow<T> requireComplete(HotWindow<T> window) {
        if (window == null) {
            throw new AssertionError("Expected complete hot-route coverage but was null");
        }
        window.completeItems(coverage -> new AssertionError(
                "Expected complete hot-route coverage but was " + coverage
        ));
        return window;
    }

    public static RouteCoverage requireComplete(RouteCoverage coverage) {
        if (coverage == null || !coverage.complete()) {
            throw new AssertionError("Expected complete hot-route coverage but was " + coverage);
        }
        return coverage;
    }

    private static void requireStatus(HotLookup<?> lookup, HotLookupStatus expected) {
        if (lookup == null || lookup.status() != expected) {
            throw new AssertionError("Expected " + expected + " but was "
                    + (lookup == null ? "null" : lookup.status()));
        }
    }
}

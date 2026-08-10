package com.reactor.cachedb.core.repository;

import com.reactor.cachedb.core.route.RouteCoverage;

import java.util.List;
import java.util.function.Function;

/** Redis-only route result plus explicit coverage evidence. */
public record HotWindow<T>(List<T> items, String nextCursor, RouteCoverage coverage) {
    public HotWindow {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor;
        if (coverage == null) {
            throw new IllegalArgumentException("coverage must not be null");
        }
    }

    public boolean complete() {
        return coverage.complete();
    }

    /**
     * Returns the route items only when Redis coverage is complete and fresh.
     * This is the safe default for application endpoints that must not expose
     * a partial hot window as a successful response.
     */
    public List<T> completeItems() {
        return completeItems(HotRouteUnavailableException::new);
    }

    /**
     * Returns the route items or maps incomplete coverage to an application
     * exception without losing the route evidence.
     */
    public <X extends Throwable> List<T> completeItems(
            Function<RouteCoverage, ? extends X> exceptionFactory
    ) throws X {
        if (complete()) {
            return items;
        }
        if (exceptionFactory == null) {
            throw new IllegalArgumentException("exceptionFactory must not be null");
        }
        X failure = exceptionFactory.apply(coverage);
        if (failure == null) {
            throw new IllegalArgumentException("exceptionFactory must not return null");
        }
        throw failure;
    }
}

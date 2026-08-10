package com.reactor.cachedb.core.repository;

import com.reactor.cachedb.core.route.RouteCoverage;

import java.util.Objects;

/** Raised when an application requires a complete Redis route that is not ready. */
public final class HotRouteUnavailableException extends IllegalStateException {
    private final RouteCoverage coverage;

    public HotRouteUnavailableException(RouteCoverage coverage) {
        super(message(coverage));
        this.coverage = Objects.requireNonNull(coverage, "coverage");
    }

    public RouteCoverage coverage() {
        return coverage;
    }

    private static String message(RouteCoverage coverage) {
        Objects.requireNonNull(coverage, "coverage");
        String detail = coverage.detail().isBlank() ? "" : ": " + coverage.detail();
        return "Redis route " + coverage.routeName() + " scope=" + coverage.scope()
                + " is not ready (" + coverage.status() + ")" + detail
                + ". Warm the route or use an explicit durable-source endpoint.";
    }
}

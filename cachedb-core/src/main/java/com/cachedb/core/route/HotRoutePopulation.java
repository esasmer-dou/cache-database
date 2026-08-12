package com.reactor.cachedb.core.route;

/** Operational source used to establish a Redis-only route's representative data set. */
public enum HotRoutePopulation {
    NOT_APPLICABLE,
    ON_DEMAND,
    DECLARED_WARM,
    WRITE_FED,
    EXTERNAL
}

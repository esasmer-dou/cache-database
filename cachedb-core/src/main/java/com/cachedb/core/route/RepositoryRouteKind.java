package com.reactor.cachedb.core.route;

/** Compile-time classified application-facing repository surface. */
public enum RepositoryRouteKind {
    HOT,
    SOURCE,
    SOURCE_SQL,
    WARM,
    LOOKUP,
    COMMAND
}

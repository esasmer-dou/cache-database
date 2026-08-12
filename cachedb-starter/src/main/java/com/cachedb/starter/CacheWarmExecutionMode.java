package com.reactor.cachedb.starter;

/** Explicitly selects whether a warm plan mutates Redis. */
public enum CacheWarmExecutionMode {
    APPLY,
    DRY_RUN
}

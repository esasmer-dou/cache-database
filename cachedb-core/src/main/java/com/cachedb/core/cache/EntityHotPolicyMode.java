package com.reactor.cachedb.core.cache;

public enum EntityHotPolicyMode {
    COUNT_WINDOW,
    TIME_WINDOW,
    STATE_WINDOW,
    CUSTOM_PREDICATE,
    COMPOSITE
}

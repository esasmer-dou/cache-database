package com.reactor.cachedb.core.repository;

public enum HotLookupStatus {
    HIT,
    NOT_CACHED,
    TOMBSTONED,
    OUTSIDE_HOT_POLICY
}

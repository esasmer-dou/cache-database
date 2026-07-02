package com.reactor.cachedb.core.config;

public enum ReadThroughMode {
    REDIS_ONLY,
    READ_THROUGH_BY_ID,
    READ_THROUGH_PAGE,
    READ_THROUGH_QUERY,
    PROJECTION_ONLY;

    public boolean byIdEnabled() {
        return this == READ_THROUGH_BY_ID || this == READ_THROUGH_QUERY;
    }

    public boolean pageEnabled() {
        return this == READ_THROUGH_PAGE || this == READ_THROUGH_QUERY;
    }

    public boolean queryEnabled() {
        return this == READ_THROUGH_QUERY;
    }

    public boolean projectionOnly() {
        return this == PROJECTION_ONLY;
    }
}

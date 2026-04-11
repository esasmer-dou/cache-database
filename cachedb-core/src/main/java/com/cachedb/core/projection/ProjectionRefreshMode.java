package com.reactor.cachedb.core.projection;

public enum ProjectionRefreshMode {
    SYNC,
    ASYNC;

    public boolean isAsync() {
        return this == ASYNC;
    }
}

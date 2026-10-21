package com.reactor.cachedb.core.config;

/** CLIENT_ONLY requires at least one independently running worker deployment. */
public enum CacheRuntimeRole {
    FULL,
    CLIENT_ONLY,
    WORKER_ONLY;

    public boolean runsWorkers() {
        return this != CLIENT_ONLY;
    }
}

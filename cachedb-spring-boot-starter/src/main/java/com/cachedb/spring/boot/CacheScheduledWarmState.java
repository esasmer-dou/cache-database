package com.reactor.cachedb.spring.boot;

public enum CacheScheduledWarmState {
    REGISTERED,
    RUNNING,
    COMPLETED,
    SKIPPED_NOT_DUE,
    SKIPPED_LOCK_TIMEOUT,
    FAILED,
    LEASE_LOST
}

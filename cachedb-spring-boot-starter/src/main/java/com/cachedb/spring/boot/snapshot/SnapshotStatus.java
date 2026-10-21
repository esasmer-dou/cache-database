package com.reactor.cachedb.spring.boot.snapshot;

public enum SnapshotStatus {
    FOUND,
    STALE_WARNING,
    ABSENT_IN_GENERATION,
    SNAPSHOT_UNAVAILABLE
}

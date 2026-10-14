package com.reactor.cachedb.spring.boot.snapshot;

/** Application-facing snapshot operations, separate from the framework lifecycle. */
public interface SnapshotOperations {
    SnapshotRepository repository(String name);

    SnapshotSettings settings(String name);

    SnapshotRefreshResult refresh(String name, boolean manual);

    default SnapshotRefreshResult refreshRoots(String name, java.util.Collection<String> rootIds) {
        throw new UnsupportedOperationException("Partial root refresh is unavailable");
    }
}

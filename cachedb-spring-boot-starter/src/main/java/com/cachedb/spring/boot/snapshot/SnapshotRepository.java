package com.reactor.cachedb.spring.boot.snapshot;

import java.util.Optional;

/** ID-only access to a completed projection; absence never triggers JDBC. */
public interface SnapshotRepository {
    Optional<SnapshotValue> findById(String id);

    default com.reactor.cachedb.core.repository.CursorPage<SnapshotValue> findPage(
            String partitionKey, com.reactor.cachedb.core.repository.WindowRequest window) {
        throw new UnsupportedOperationException("Snapshot plan has no sorted index");
    }

    default SnapshotRefreshResult refreshRoots(java.util.Collection<String> rootIds) {
        throw new UnsupportedOperationException("Snapshot repository has no refresh coordinator");
    }
}

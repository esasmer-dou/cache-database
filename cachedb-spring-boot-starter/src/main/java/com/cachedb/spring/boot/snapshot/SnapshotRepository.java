package com.reactor.cachedb.spring.boot.snapshot;

import java.util.Optional;

/** ID-only access to a completed projection; absence never triggers JDBC. */
public interface SnapshotRepository {
    Optional<SnapshotValue> findById(String id);
}

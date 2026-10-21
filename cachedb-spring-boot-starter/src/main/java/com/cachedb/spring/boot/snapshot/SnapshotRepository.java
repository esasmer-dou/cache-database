package com.reactor.cachedb.spring.boot.snapshot;

import java.util.*;
import java.util.Optional;

/** ID-only access to a completed projection; absence never triggers JDBC. */
public interface SnapshotRepository {
    Optional<SnapshotValue> findById(String id);

    default Map<String, SnapshotValue> findByIds(Collection<String> ids) {
        Map<String, SnapshotValue> result = new LinkedHashMap<>();
        for (var entry : lookupRawAll(ids).entrySet()) {
            var value = entry.getValue();
            if (value.isPresent())
                result.put(
                        entry.getKey(),
                        new SnapshotValue(
                                entry.getKey(),
                                value.rawPayload(),
                                value.refreshedAt(),
                                value.generation()));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Implementations with a generation marker override this to distinguish absence. */
    default SnapshotLookup<String> lookupRaw(String id) {
        var value = findById(id);
        return value.isPresent()
                ? describe(value.get())
                : new SnapshotLookup<>(
                        id, SnapshotStatus.SNAPSHOT_UNAVAILABLE, List.of(), null, 0, 0, null);
    }

    default Map<String, SnapshotLookup<String>> lookupRawAll(Collection<String> ids) {
        if (ids.size() > 1000) throw new IllegalArgumentException("At most 1000 snapshot IDs");
        Map<String, SnapshotLookup<String>> result = new LinkedHashMap<>();
        for (String id : ids) result.put(id, lookupRaw(id));
        return Collections.unmodifiableMap(result);
    }

    default SnapshotLookup<String> describe(SnapshotValue value) {
        return new SnapshotLookup<>(
                value.id(),
                SnapshotStatus.FOUND,
                List.of(value.payload()),
                value.payload(),
                value.refreshedAt(),
                0,
                value.generation());
    }

    default com.reactor.cachedb.core.repository.CursorPage<SnapshotValue> findPage(
            String partitionKey, com.reactor.cachedb.core.repository.WindowRequest window) {
        throw new UnsupportedOperationException("Snapshot plan has no sorted index");
    }

    default SnapshotRefreshResult refreshRoots(java.util.Collection<String> rootIds) {
        throw new UnsupportedOperationException("Snapshot repository has no refresh coordinator");
    }
}

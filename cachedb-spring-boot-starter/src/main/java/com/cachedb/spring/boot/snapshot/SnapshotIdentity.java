package com.reactor.cachedb.spring.boot.snapshot;

import java.util.function.Function;

/** No method-handle bootstrap for the default string ID route. */
final class SnapshotIdentity implements Function<String, String> {
    static final SnapshotIdentity INSTANCE = new SnapshotIdentity();

    private SnapshotIdentity() {}

    @Override
    public String apply(String value) {
        return value;
    }
}

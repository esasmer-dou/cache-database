package com.reactor.cachedb.spring.boot.snapshot;

import java.util.Objects;
import java.util.function.Function;

public record SnapshotDirtyBinding(String entityName, Function<Object, String> rootIdExtractor, boolean rootEntity) {
    public SnapshotDirtyBinding {
        if (entityName == null || entityName.isBlank() || entityName.length() > 512)
            throw new IllegalArgumentException("Invalid auto-dirty entity name");
        Objects.requireNonNull(rootIdExtractor, "rootIdExtractor");
    }
}

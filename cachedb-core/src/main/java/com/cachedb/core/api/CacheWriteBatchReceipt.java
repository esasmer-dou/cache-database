package com.reactor.cachedb.core.api;

import java.util.List;
import java.util.Objects;

/** Acceptance receipt; SQL durability is observed separately. */
public record CacheWriteBatchReceipt(
        String batchId,
        String streamEntryId,
        List<Long> versions,
        List<com.reactor.cachedb.core.model.WriteDependency> dependencies) {
    public CacheWriteBatchReceipt(String batchId, String streamEntryId, List<Long> versions) {
        this(batchId, streamEntryId, versions, List.of());
    }

    public CacheWriteBatchReceipt {
        versions = List.copyOf(versions);
        dependencies = List.copyOf(dependencies);
    }

    // Preserve record value semantics without a first-use ObjectMethods bootstrap on replay.
    @Override
    public final boolean equals(Object other) {
        return this == other
                || other instanceof CacheWriteBatchReceipt value
                        && Objects.equals(batchId, value.batchId)
                        && Objects.equals(streamEntryId, value.streamEntryId)
                        && versions.equals(value.versions)
                        && dependencies.equals(value.dependencies);
    }

    @Override
    public final int hashCode() {
        int hash = Objects.hashCode(batchId);
        hash = 31 * hash + Objects.hashCode(streamEntryId);
        hash = 31 * hash + versions.hashCode();
        return 31 * hash + dependencies.hashCode();
    }
}

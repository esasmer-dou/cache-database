package com.reactor.cachedb.core.api;

import java.util.List;

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
}

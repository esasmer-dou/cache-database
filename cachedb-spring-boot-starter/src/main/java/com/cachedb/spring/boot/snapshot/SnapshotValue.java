package com.reactor.cachedb.spring.boot.snapshot;

/**
 * Fully prepared JSON array, with the beginning of its source snapshot and publication generation.
 */
public record SnapshotValue(String id, String payload, long refreshedAt, String generation) {}

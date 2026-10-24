package com.reactor.cachedb.spring.boot.snapshot;

/** Completion of one bounded dirty-root publication, not an SQL durability receipt. */
public record SnapshotManifest(String planName, int rootCount, SnapshotRefreshResult refresh) {}

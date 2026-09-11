package com.reactor.cachedb.spring.boot.snapshot;

/** A declared source or relation in a plan's explicit input list. */
public sealed interface SnapshotInput permits SnapshotSource, SnapshotRelation {
    SnapshotSource<?> source();
}

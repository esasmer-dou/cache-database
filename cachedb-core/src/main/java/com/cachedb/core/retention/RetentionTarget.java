package com.reactor.cachedb.core.retention;

/** Implementations must bound physical I/O and hold capacity until it exits. */
public interface RetentionTarget {
    Class<?> entityType();
    String scopeToken();
    RetentionSweepResult sweep(int maxRows);
}

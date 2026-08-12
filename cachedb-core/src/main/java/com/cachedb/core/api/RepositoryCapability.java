package com.reactor.cachedb.core.api;

/** Optional low-level repository operations that callers can inspect before invocation. */
public enum RepositoryCapability {
    VERSIONED_READ,
    WRITE_RECEIPT,
    OPTIMISTIC_WRITE,
    DEPENDENCY_AWARE_WRITE,
    BULK_WRITE,
    PARTITIONED_QUERY,
    DELETE_RECEIPT,
    PROJECTION
}

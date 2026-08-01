package com.reactor.cachedb.spring.boot;

public enum CacheDistributedJobState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

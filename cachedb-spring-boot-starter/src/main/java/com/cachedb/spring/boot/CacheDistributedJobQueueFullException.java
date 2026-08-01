package com.reactor.cachedb.spring.boot;

public final class CacheDistributedJobQueueFullException extends RuntimeException {

    public CacheDistributedJobQueueFullException(String message) {
        super(message);
    }
}

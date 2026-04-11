package com.reactor.cachedb.core.queue;

public enum WriteFailureCategory {
    CONNECTION,
    AVAILABILITY,
    TIMEOUT,
    SERIALIZATION,
    DEADLOCK,
    LOCK_CONFLICT,
    CONSTRAINT,
    DATA,
    SCHEMA,
    PERMISSION,
    UNKNOWN
}

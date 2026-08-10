package com.reactor.cachedb.core.repository;

public enum WriteConsistency {
    REDIS_ACCEPTED,
    SQL_DURABLE,
    READ_YOUR_WRITES
}

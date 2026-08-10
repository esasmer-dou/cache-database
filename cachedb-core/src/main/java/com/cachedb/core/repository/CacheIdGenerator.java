package com.reactor.cachedb.core.repository;

import java.util.UUID;

public interface CacheIdGenerator {
    UUID nextUuid();

    String nextUlid();

    long nextSequence(String sequenceName, int allocationSize);
}

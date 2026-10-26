package com.reactor.cachedb.core.codec;

/** Pure, deterministic transformation. Implementations must not perform I/O or mutate shared state. */
public interface CachePayloadUpcaster {
    int targetVersion();
    String upcast(String rawPayload, int fromVersion, int toVersion);
}

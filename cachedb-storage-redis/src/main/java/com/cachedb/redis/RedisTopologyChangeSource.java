package com.reactor.cachedb.redis;

import java.util.Objects;

/** Optional topology notification capability. Callbacks must be bounded and memory-only. */
public interface RedisTopologyChangeSource {
    AutoCloseable onTopologyChange(Runnable listener);
    void topologyChanged();

    /** Retains the original Redis failure; notification never retries or performs control I/O. */
    static void failure(RedisClientOperations redis, Throwable failure) {
        if (!(redis instanceof RedisTopologyChangeSource source)) return;
        Throwable cursor = Objects.requireNonNull(failure);
        for (int depth = 0; cursor != null && depth < 16; depth++, cursor = cursor.getCause()) {
            if (cursor instanceof redis.clients.jedis.exceptions.JedisRedirectionException) {
                source.topologyChanged(); return;
            }
        }
    }
}

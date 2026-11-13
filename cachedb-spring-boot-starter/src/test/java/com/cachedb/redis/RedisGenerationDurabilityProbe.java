package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;

/** Test-only bridge: cross-provider tests must not expand the production Redis API. */
public final class RedisGenerationDurabilityProbe {
    public static long advanceOne(RedisClientOperations redis, RedisKeyStrategy keys,
            QueuedWriteOperation operation, Generation generation) {
        return RedisGuardedDurability.advanceOne(redis, keys, operation, generation);
    }

    private RedisGenerationDurabilityProbe() {}
}

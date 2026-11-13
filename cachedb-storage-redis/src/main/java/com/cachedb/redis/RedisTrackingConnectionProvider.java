package com.reactor.cachedb.redis;

import java.util.List;

/**
 * Opt-in capability, deliberately separate from the shared RedisClientOperations SPI.
 * Factories and their credential/TLS/mapper/destruction callbacks must honor virtual-thread
 * interruption and finite deadlines. Uninterruptible custom/native callbacks are unsupported;
 * no timeout helper can safely reclaim a socket still owned by such a callback.
 */
public interface RedisTrackingConnectionProvider {
    /** Memory-only snapshot of all known pools, including replicas; no pool borrowing. */
    List<RedisTrackingNode> trackingNodes(int maximumNodes);
    default boolean trackingClustered() { return false; }
}

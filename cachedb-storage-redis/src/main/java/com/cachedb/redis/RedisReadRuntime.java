package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.util.concurrent.CompletableFuture;

/**
 * One shared raw point-read runtime per client/pool. Closing this wrapper does not close the client.
 * Apply causal/unit-of-work bypasses and caller-side context/policy outside this transport boundary.
 */
public final class RedisReadRuntime implements AutoCloseable {
    private final RedisReadCoalescer coalescer;

    public RedisReadRuntime(RedisClientOperations client, int maxPool) {
        this(client, RedisReadCoalescingConfig.defaults(), maxPool);
    }

    public RedisReadRuntime(RedisClientOperations client, RedisReadCoalescingConfig config, int maxPool) {
        coalescer = new RedisReadCoalescer(client, config, maxPool);
    }

    public RedisPointRead readSync(String entityKey, String tombstoneKey, boolean needTtl) {
        return coalescer.readSync(entityKey, tombstoneKey, needTtl);
    }

    public CompletableFuture<RedisPointRead> readAsync(String entityKey, String tombstoneKey, boolean needTtl) {
        return coalescer.readAsync(entityKey, tombstoneKey, needTtl);
    }

    /** Redis adapter: cancellation of delivery is not physical I/O completion. */
    CompletableFuture<Void> physicalCompletion(CompletableFuture<RedisPointRead> read) {
        return coalescer.physicalCompletion(read);
    }

    public Metrics metrics() { return coalescer.metrics(); }

    /** Separate bounded caller-owned waiting; these requests have not acquired physical I/O. */
    public SyncOverflowWaits syncOverflowWaits() { return coalescer.syncOverflowWaits(); }
    public record SyncOverflowWaits(int waiters, long retainedBytes, int limit, long byteLimit) {}

    /** Reserved I/O reply capacity plus raw content retained by in-progress completion callbacks. */
    public DeliveryMemory deliveryMemory() { return coalescer.deliveryMemory(); }
    public record DeliveryMemory(long reservedBytes, long limitBytes, int batches) {}

    public record Metrics(long batches, long coalescedRequests, double averageBatchSize, long bypassed) {}

    @Override public void close() { coalescer.close(); }
}

package com.reactor.cachedb.core.queue;

public final class RedisBackpressureException extends IllegalStateException {

    public RedisBackpressureException(RedisGuardrailSnapshot snapshot) {
        super("Redis write rejected under critical pressure: usedMemoryBytes=" + snapshot.usedMemoryBytes()
                + ", writeBehindBacklog=" + snapshot.writeBehindBacklog()
                + ", compactionPending=" + snapshot.compactionPendingCount()
                + ", compactionPayload=" + snapshot.compactionPayloadCount());
    }
}

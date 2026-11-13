package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.config.RedisReadCoalescingConfig;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

class RedisRepositoryReadStateTest {
    private static RedisClientOperations unused(AtomicInteger closes) {
        return new RedisClientOperations() {
            public UnifiedJedis commands() { throw new AssertionError("Policy/capacity validation must be memory-only"); }
            public void close() { closes.incrementAndGet(); }
        };
    }
    @Test void unknownCustomCapacityIsTwoWithoutOpeningAConnection() {
        var closes = new AtomicInteger(); var client = unused(closes);
        assertEquals(2, client.physicalPoolCapacity());
        try (var state = new RedisRepositoryReadState(client, RedisReadCoalescingConfig.builder().enabled(true).build())) {
            assertEquals(2, state.poolCapacity());
            assertTrue(state.coalesce(0));
        }
        assertEquals(0, closes.get(), "Closing read state must not close the borrowed Redis client");
    }
    @Test void callerPolicyBypassIsSeparateFromTransportOverflow() {
        var closes = new AtomicInteger();
        try (var state = new RedisRepositoryReadState(unused(closes), RedisReadCoalescingConfig.builder().enabled(true).build())) {
            assertFalse(state.coalesce(4));
            CacheWriteBatch batch = new CacheWriteBatch() {
                public <T> CacheWriteBatch put(T entity) { throw new AssertionError(); }
                public <T> CacheWriteBatch put(T entity, long version) { throw new AssertionError(); }
                public <T, ID> CacheWriteBatch delete(Class<T> type, ID id) { throw new AssertionError(); }
                public CacheWriteBatchReceipt commit() { throw new AssertionError(); }
            };
            try (var work = CacheUnitOfWork.begin(this, batch)) { assertFalse(state.coalesce(0)); }
            var metrics = state.metrics();
            assertEquals(1, metrics.causal()); assertEquals(1, metrics.unitOfWork());
            assertEquals(2, metrics.bypassed()); assertEquals(0, metrics.transport().bypassed());
            assertEquals(0, metrics.transport().batches());
        }
    }
    @Test void l1AdmissionCheckIsMemoryOnlyAndDoesNotCountTransportOrBypass() {
        var closes = new AtomicInteger();
        var state = new RedisRepositoryReadState(unused(closes), RedisReadCoalescingConfig.defaults());
        try {
            state.ensureOpen(); state.ensureOpen();
            assertEquals(0, state.metrics().bypassed());
            assertEquals(0, state.metrics().transport().batches());
            state.close();
            assertThrows(RejectedExecutionException.class, state::ensureOpen);
            assertEquals(0, closes.get());
        } finally { state.close(); }
    }
    @Test void disabledPathIsCountedAndCannotEscapeClosure() {
        var state = new RedisRepositoryReadState(unused(new AtomicInteger()),
                RedisReadCoalescingConfig.builder().enabled(false).build());
        assertFalse(state.coalesce(0)); assertEquals(1, state.metrics().disabled());
        state.close(); state.close();
        assertThrows(RejectedExecutionException.class, () -> state.coalesce(0));
        assertThrows(RejectedExecutionException.class, () -> state.coalesce(5));
    }
}

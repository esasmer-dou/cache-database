package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.redis.RedisClientOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.UnifiedJedis;

@Timeout(30)
class CacheDbCacheSubscriptionTest {
    @Test void interruptedAndReconnectedAttemptsDestroyEachOwnedConnection() {
        try (var transport = new CacheDbCacheTestSubscription()) {
            var coordinator = new CacheDbCacheInvalidationCoordinator(transport.operations, "owned");
            try {
                coordinator.register("cache", CacheDbCacheRedisTest.slowNear(false));
                coordinator.freezeAndStartIfNearEnabled();
                CacheDbCacheRedisFixture.await(coordinator::connected);
                assertEquals(1, transport.attempts.get());
                coordinator.redisFailure();
                CacheDbCacheRedisFixture.await(() -> transport.destroyed.get() == 1);
                transport.allowReconnect.countDown();
                CacheDbCacheRedisFixture.await(coordinator::connected);
                assertEquals(2, transport.attempts.get());
            } finally { coordinator.close(); }
            CacheDbCacheRedisFixture.await(() -> transport.destroyed.get() == 2);
            verify(transport.operations, never()).commands();
        }
    }

    @Test void anOpaqueClientWithoutAnOwnedConnectionFactoryCannotBorrowASubscription() {
        RedisClientOperations operations = mock(RedisClientOperations.class);
        UnifiedJedis commands = mock(UnifiedJedis.class);
        when(operations.commands()).thenReturn(commands);
        assertThrows(IllegalStateException.class,
                () -> CacheDbCacheSubscription.listen(operations, new JedisPubSub() {}, "owned"));
        verifyNoInteractions(commands);
    }
}

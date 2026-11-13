package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisMovedDataException;

class RedisTopologyNotificationTest {
    static final class Source implements RedisClientOperations, RedisTopologyChangeSource {
        final AtomicInteger events = new AtomicInteger(), commands = new AtomicInteger();
        final List<Runnable> listeners = new ArrayList<>();
        public UnifiedJedis commands() { commands.incrementAndGet(); throw new AssertionError("No topology control I/O on caller"); }
        public AutoCloseable onTopologyChange(Runnable listener) { listeners.add(listener); return () -> listeners.remove(listener); }
        public void topologyChanged() { events.incrementAndGet(); for (var listener : List.copyOf(listeners)) listener.run(); }
    }
    @Test void movedAndWrappedMovedNotifyWithoutRetryOrStorageAccess() {
        var source = new Source();
        var moved = new JedisMovedDataException("MOVED 1 127.0.0.1:6379", new HostAndPort("127.0.0.1", 6379), 1);
        RedisTopologyChangeSource.failure(source, moved);
        RedisTopologyChangeSource.failure(source, new IllegalStateException(moved));
        assertEquals(2, source.events.get()); assertEquals(0, source.commands.get());
        RedisTopologyChangeSource.failure(source, new IllegalStateException("Not a redirection"));
        assertEquals(2, source.events.get());
    }
    @Test void causeWalkingIsBoundedAndDoesNotParseUntrustedErrorText() {
        var source = new Source();
        var a = new IllegalStateException("MOVED is merely untrusted message text");
        var z = new IllegalStateException("not a typed redirection");
        a.initCause(z); z.initCause(a);
        RedisTopologyChangeSource.failure(source, a);
        assertEquals(0, source.events.get()); assertEquals(0, source.commands.get());
        Throwable deep = new JedisMovedDataException("MOVED", new HostAndPort("127.0.0.1", 6379), 1);
        for (int i = 0; i < 17; i++) deep = new IllegalStateException(deep);
        RedisTopologyChangeSource.failure(source, deep);
        assertEquals(0, source.events.get());
    }
    @Test void notificationFlushesExistingStoresSynchronously() throws Exception {
        var source = new Source();
        try (var near = new RedisNearCacheCoordinator(source, new RedisKeyStrategy("topology"));
                var registration = source.onTopologyChange(near::topologyChanged)) {
            var store = near.register("rows", new com.reactor.cachedb.core.cache.NearCachePolicy(16, 10, 65536));
            store.put("1", "old", -1, store.capture("1"));
            assertNotNull(store.get("1"));
            source.topologyChanged();
            assertNull(store.get("1")); assertFalse(near.connected());
            assertEquals(0, source.commands.get());
        }
    }
}

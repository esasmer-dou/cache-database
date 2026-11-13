package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.redis.RedisClientOperations;
import com.reactor.cachedb.redis.RedisTrackingConnectionProvider;
import com.reactor.cachedb.redis.RedisTrackingNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import redis.clients.jedis.Connection;
import redis.clients.jedis.exceptions.JedisConnectionException;

/** Controllable RESP transport; coordinator state and subscription ownership remain real. */
final class CacheDbCacheTestSubscription implements AutoCloseable {
    final RedisClientOperations operations = mock(RedisClientOperations.class,
            withSettings().extraInterfaces(RedisTrackingConnectionProvider.class));
    final AtomicInteger attempts = new AtomicInteger(), destroyed = new AtomicInteger();
    private record Reply(Object frame, CountDownLatch processed) {}
    final AtomicReference<ArrayBlockingQueue<Reply>> replies = new AtomicReference<>();
    final CountDownLatch disconnect = new CountDownLatch(1), allowReconnect = new CountDownLatch(1), stop = new CountDownLatch(1);

    @SuppressWarnings("unchecked")
    CacheDbCacheTestSubscription() {
        PooledObjectFactory<Connection> factory = mock(PooledObjectFactory.class);
        when(((RedisTrackingConnectionProvider) operations).trackingNodes(64))
                .thenReturn(List.of(new RedisTrackingNode("fixture", factory)));
        try {
            when(factory.makeObject()).thenAnswer(invocation -> {
                int attempt = attempts.incrementAndGet();
                Connection connection = mock(Connection.class);
                var queue = new ArrayBlockingQueue<Reply>(64);
                var delivered = new AtomicReference<CountDownLatch>();
                replies.set(queue);
                var reads = new AtomicInteger();
                when(connection.getUnflushedObject()).thenAnswer(read -> {
                    try {
                        CountDownLatch completed = delivered.getAndSet(null);
                        if (completed != null) completed.countDown();
                        if (reads.getAndIncrement() == 0) {
                            if (attempt > 1 && !allowReconnect.await(20, TimeUnit.SECONDS))
                                throw new JedisConnectionException("fixture reconnect timed out");
                            return frame("subscribe", "fixture", 1L);
                        }
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                        while (System.nanoTime() < deadline) {
                            if (attempt == 1 && disconnect.getCount() == 0)
                                throw new JedisConnectionException("fixture disconnect");
                            if (stop.getCount() == 0) return frame("unsubscribe", "fixture", 0L);
                            Reply event = queue.poll(5, TimeUnit.MILLISECONDS);
                            if (event != null) {
                                delivered.set(event.processed());
                                return event.frame();
                            }
                        }
                        throw new JedisConnectionException("fixture timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new JedisConnectionException("fixture interrupted");
                    }
                });
                return new DefaultPooledObject<>(connection);
            });
            doAnswer(invocation -> { destroyed.incrementAndGet(); return null; })
                    .when(factory).destroyObject(any());
        } catch (Exception invalid) { throw new AssertionError(invalid); }
    }
    private static List<Object> frame(String kind, String channel, Object value) {
        return List.of(kind.getBytes(StandardCharsets.UTF_8), channel.getBytes(StandardCharsets.UTF_8),
                value instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : value);
    }
    void event(String channel, String message) {
        var queue = replies.get();
        assertNotNull(queue);
        var processed = new CountDownLatch(1);
        assertTrue(queue.offer(new Reply(frame("message", channel, message), processed)));
        try { assertTrue(processed.await(10, TimeUnit.SECONDS), "Notification callback did not complete"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
    @Override public void close() { disconnect.countDown(); allowReconnect.countDown(); stop.countDown(); }
}

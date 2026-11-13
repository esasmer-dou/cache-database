package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.cache.NearCachePolicy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class Java21VirtualThreadReadinessTest {
    @Test void refreshIsVirtualBoundedDeduplicatedAndReleasesReservationsOnShutdown() throws Exception {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var coordinator = new RedisNearCacheCoordinator(JedisPooledOperations.of(redis),
                        new RedisKeyStrategy("virtual-readiness-" + UUID.randomUUID()))) {
            assertFalse(coordinator.refreshExecutorCreated());
            var store = coordinator.register("rows", new NearCachePolicy(32, 10, 65536, 1));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!coordinator.connected() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(coordinator.connected());
            assertFalse(coordinator.refreshExecutorCreated());
            var gate = new CountDownLatch(1);
            var sameStarted = new CountDownLatch(1);
            var sameCalls = new AtomicInteger();
            var workers = new ConcurrentLinkedQueue<Thread>();
            Runnable hold = () -> {
                workers.add(Thread.currentThread());
                try { gate.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            };
            coordinator.refresh(store, "same", () -> { sameCalls.incrementAndGet(); sameStarted.countDown(); hold.run(); });
            assertTrue(sameStarted.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) coordinator.refresh(store, "same", hold);
            assertEquals(257, coordinator.availableRefreshPermits());
            for (int i = 0; i < 600; i++) coordinator.refresh(store, "other-" + i, hold);
            assertEquals(0, coordinator.availableRefreshPermits());
            assertTrue(coordinator.rejectedRefreshes() > 0);
            assertTrue(workers.size() <= 2, "Refresh I/O must not consume the entire Redis pool");
            try {
                assertTimeout(Duration.ofSeconds(3), coordinator::close);
            } finally { gate.countDown(); }
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((coordinator.availableRefreshPermits() != 258 || workers.stream().anyMatch(Thread::isAlive))
                    && System.nanoTime() < deadline) Thread.sleep(2);
            assertEquals(258, coordinator.availableRefreshPermits());
            assertEquals(1, sameCalls.get());
            assertTrue(workers.size() <= 258);
            assertTrue(workers.stream().allMatch(Thread::isVirtual));
            assertTrue(workers.stream().noneMatch(Thread::isAlive));
            coordinator.close();
        }
    }

    @Test void refreshFailureDoesNotLeakPermitOrPoisonLaterWork() throws Exception {
        try (var redis = new JedisPooled(RedisTestEndpoint.uri());
                var coordinator = new RedisNearCacheCoordinator(JedisPooledOperations.of(redis),
                        new RedisKeyStrategy("virtual-failure-" + UUID.randomUUID()))) {
            var store = coordinator.register("rows", new NearCachePolicy(32, 10, 65536));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!coordinator.connected() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(coordinator.connected());
            coordinator.refresh(store, "failure", () -> { throw new IllegalStateException("test failure"); });
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((coordinator.failedRefreshes() != 1 || coordinator.availableRefreshPermits() != 258)
                    && System.nanoTime() < deadline) Thread.sleep(2);
            assertEquals(1, coordinator.failedRefreshes());
            assertEquals(258, coordinator.availableRefreshPermits());
            var completed = new CountDownLatch(1);
            coordinator.refresh(store, "next", completed::countDown);
            assertTrue(completed.await(2, TimeUnit.SECONDS));
        }
    }
}

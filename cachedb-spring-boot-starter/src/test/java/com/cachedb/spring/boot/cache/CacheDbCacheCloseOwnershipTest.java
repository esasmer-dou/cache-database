package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.redis.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisConnectionException;

@Timeout(15)
class CacheDbCacheCloseOwnershipTest {
    enum Phase { CREATE, LATE_CREATE, IDLE, LATE_DESTROY, FAIL_DESTROY, FAIL_STARTUP_CLEANUP }

    @Test void cooperativeCreationCancellationNeverStartsASubscriber() throws Exception {
        try (var fixture = new Fixture(Phase.CREATE)) {
            fixture.start();
            assertTrue(fixture.factoryEntered.await(2, TimeUnit.SECONDS));
            fixture.coordinator.close();
            assertFalse(fixture.coordinator.listenerAlive());
            assertFalse(fixture.coordinator.cleanupFailed());
            assertEquals(1, fixture.creations.get());
            assertEquals(0, fixture.objects.get());
            verify(fixture.connection, never()).setTimeoutInfinite();
        }
    }

    @Test void lateFactoryResultIsDestroyedByItsOwnerButCannotEraseAStopTimeout() throws Exception {
        try (var fixture = new Fixture(Phase.LATE_CREATE)) {
            fixture.start();
            assertTrue(fixture.factoryEntered.await(2, TimeUnit.SECONDS));
            var failure = assertThrows(IllegalStateException.class, fixture.coordinator::close);
            assertTrue(fixture.coordinator.cleanupFailed());
            assertTrue(fixture.coordinator.listenerAlive());
            assertFalse(fixture.coordinator.connected());
            fixture.release.countDown();
            CacheDbCacheRedisFixture.await(() -> !fixture.coordinator.listenerAlive());
            assertEquals(1, fixture.creations.get());
            assertEquals(1, fixture.destroyed.get());
            assertSame(fixture.creator.get(), fixture.destroyer.get());
            verify(fixture.connection, never()).setTimeoutInfinite();
            assertSame(failure, assertThrows(IllegalStateException.class, fixture.coordinator::close));
        }
    }

    @Test void destructionBlackholeFailsBoundedCloseAndNeverReportsLaterSuccess() throws Exception {
        try (var fixture = new Fixture(Phase.LATE_DESTROY)) {
            fixture.start();
            CacheDbCacheRedisFixture.await(fixture.coordinator::connected);
            long begin = System.nanoTime();
            var failure = assertThrows(IllegalStateException.class, fixture.coordinator::close);
            assertTrue(System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(5));
            assertEquals(0, fixture.destroyEntered.getCount());
            assertTrue(fixture.coordinator.listenerAlive());
            assertTrue(fixture.coordinator.cleanupFailed());
            assertFalse(fixture.coordinator.connected());
            fixture.release.countDown();
            CacheDbCacheRedisFixture.await(() -> !fixture.coordinator.listenerAlive());
            assertEquals(1, fixture.destroyed.get());
            assertSame(failure, assertThrows(IllegalStateException.class, fixture.coordinator::close));
        }
    }

    @Test void failedDestructionIsStickyAndDoesNotReconnect() {
        try (var fixture = new Fixture(Phase.FAIL_DESTROY)) {
            fixture.start();
            CacheDbCacheRedisFixture.await(fixture.coordinator::connected);
            fixture.coordinator.redisFailure();
            CacheDbCacheRedisFixture.await(fixture.coordinator::cleanupFailed);
            CacheDbCacheRedisFixture.await(() -> !fixture.coordinator.listenerAlive());
            var failure = assertThrows(IllegalStateException.class, fixture.coordinator::close);
            assertSame(failure, assertThrows(IllegalStateException.class, fixture.coordinator::close));
            assertEquals(1, fixture.creations.get());
            assertEquals(1, fixture.destroyed.get());
            verify(fixture.operations, never()).commands();
        }
    }

    @Test void failedCleanupBeforeOpenPublicationCannotBeMistakenForARetryableConnectFailure() {
        try (var fixture = new Fixture(Phase.FAIL_STARTUP_CLEANUP)) {
            fixture.start();
            CacheDbCacheRedisFixture.await(fixture.coordinator::cleanupFailed);
            CacheDbCacheRedisFixture.await(() -> !fixture.coordinator.listenerAlive());
            assertThrows(IllegalStateException.class, fixture.coordinator::close);
            assertEquals(1, fixture.creations.get());
            assertEquals(1, fixture.destroyed.get());
            verify(fixture.connection, never()).setTimeoutInfinite();
        }
    }

    @Test void interruptedCloseCallerStillConfirmsQuiescenceAndKeepsItsInterruptFlag() {
        try (var fixture = new Fixture(Phase.IDLE)) {
            fixture.start();
            CacheDbCacheRedisFixture.await(fixture.coordinator::connected);
            try {
                Thread.currentThread().interrupt();
                fixture.coordinator.close();
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            assertFalse(fixture.coordinator.listenerAlive());
            assertFalse(fixture.coordinator.cleanupFailed());
            assertEquals(1, fixture.destroyed.get());
            assertSame(fixture.creator.get(), fixture.destroyer.get());
        }
    }

    @Test void managerRepeatedCloseCannotHideCoordinatorCleanupFailure() {
        try (var fixture = new Fixture(Phase.FAIL_DESTROY)) {
            var manager = new CacheDbCacheManager(fixture.operations, fixture.operations, "owned-close",
                    Map.of("cache", CacheDbCacheRedisTest.slowNear(false)), List.of(), 16,
                    new com.reactor.cachedb.core.config.SingleFlightConfig(true, 16, 16, 1000));
            try {
                CacheDbCacheRedisFixture.await(manager::invalidationConnected);
                var failure = assertThrows(IllegalStateException.class, manager::close);
                assertSame(failure, assertThrows(IllegalStateException.class, manager::close));
                assertFalse(manager.invalidationConnected());
                assertEquals(1, fixture.destroyed.get());
            } finally {
                try { manager.close(); }
                catch (IllegalStateException expected) { assertTrue(manager.invalidationCoordinator().cleanupFailed()); }
            }
        }
    }

    @Test void closeBeforeStartupCannotPublishAnIoOwnerLater() {
        try (var fixture = new Fixture(Phase.IDLE)) {
            fixture.coordinator.close();
            fixture.start();
            assertFalse(fixture.coordinator.listenerAlive());
            assertFalse(fixture.coordinator.cleanupFailed());
            assertEquals(0, fixture.creations.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger creations = new AtomicInteger(), objects = new AtomicInteger(), destroyed = new AtomicInteger();
        final AtomicReference<Thread> creator = new AtomicReference<>(), destroyer = new AtomicReference<>();
        final CountDownLatch factoryEntered = new CountDownLatch(1), destroyEntered = new CountDownLatch(1),
                release = new CountDownLatch(1), receive = new CountDownLatch(1);
        final Connection connection = mock(Connection.class);
        final RedisClientOperations operations = mock(RedisClientOperations.class,
                withSettings().extraInterfaces(RedisTrackingConnectionProvider.class));
        final CacheDbCacheInvalidationCoordinator coordinator = new CacheDbCacheInvalidationCoordinator(operations, "owned-close");
        @SuppressWarnings("unchecked")
        Fixture(Phase phase) {
            try {
                PooledObjectFactory<Connection> factory = mock(PooledObjectFactory.class);
                when(((RedisTrackingConnectionProvider) operations).trackingNodes(64))
                        .thenReturn(List.of(new RedisTrackingNode("fixture", factory)));
                var connected = new AtomicBoolean(true);
                when(connection.isConnected()).thenAnswer(call -> connected.get());
                doAnswer(call -> { connected.set(false); return null; }).when(connection).close();
                when(connection.getRedisProtocol()).thenReturn(phase == Phase.FAIL_STARTUP_CLEANUP ? RedisProtocol.RESP3 : RedisProtocol.RESP2);
                when(factory.makeObject()).thenAnswer(call -> {
                    creations.incrementAndGet(); creator.set(Thread.currentThread()); factoryEntered.countDown();
                    if (phase == Phase.CREATE) release.await(10, TimeUnit.SECONDS);
                    else if (phase == Phase.LATE_CREATE) ignoringInterrupts(release);
                    objects.incrementAndGet();
                    return new DefaultPooledObject<>(connection);
                });
                var reads = new AtomicInteger();
                when(connection.getUnflushedObject()).thenAnswer(call -> {
                    if (reads.getAndIncrement() == 0)
                        return List.of("subscribe".getBytes(StandardCharsets.UTF_8), "fixture".getBytes(StandardCharsets.UTF_8), 1L);
                    try { receive.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        throw new JedisConnectionException("fixture receive cancelled");
                    }
                    throw new JedisConnectionException("fixture receive expired");
                });
                doAnswer(call -> {
                    destroyed.incrementAndGet(); destroyer.set(Thread.currentThread()); destroyEntered.countDown();
                    if (phase == Phase.LATE_DESTROY) ignoringInterrupts(release);
                    if (phase == Phase.FAIL_DESTROY || phase == Phase.FAIL_STARTUP_CLEANUP)
                        throw new IllegalStateException("fixture refused destruction");
                    return null;
                }).when(factory).destroyObject(any(PooledObject.class));
            } catch (Exception invalid) { throw new AssertionError(invalid); }
        }
        void start() {
            coordinator.register("cache", CacheDbCacheRedisTest.slowNear(false));
            coordinator.freezeAndStartIfNearEnabled();
        }
        private static void ignoringInterrupts(CountDownLatch latch) throws InterruptedException {
            boolean interrupted = false;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try {
                while (latch.getCount() > 0) {
                    long remaining = until - System.nanoTime();
                    if (remaining <= 0) throw new AssertionError("Unreleased bounded fixture barrier");
                    try { latch.await(remaining, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException cancelled) { interrupted = true; }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        @Override public void close() {
            release.countDown(); receive.countDown();
            try { coordinator.close(); }
            catch (IllegalStateException failure) { if (!coordinator.cleanupFailed()) throw failure; }
            CacheDbCacheRedisFixture.await(() -> !coordinator.listenerAlive());
        }
    }
}

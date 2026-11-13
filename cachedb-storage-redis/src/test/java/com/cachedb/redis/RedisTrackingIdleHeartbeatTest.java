package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.config.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.util.JedisURIHelper;

/** Real idle sockets; no writes or PUBLISH to keep the subscription alive. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisTrackingIdleHeartbeatTest {
    @Test @Timeout(45) void trackingSurvivesTwoLegacyBlockingTimeoutsWithoutReconnect() throws Exception {
        idle(NearCacheInvalidationMode.SERVER_TRACKING, false);
    }
    @Test @Timeout(45) void explicitPubsubSurvivesTwoLegacyBlockingTimeoutsWithoutReconnect() throws Exception {
        idle(NearCacheInvalidationMode.PUBSUB, false);
    }
    @Test @Timeout(45) void healthyAutoFallbackDoesNotCycleAtRetryCooldown() throws Exception {
        idle(NearCacheInvalidationMode.AUTO, true);
    }
    private static void await(BooleanSupplier condition, long millis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && end - System.nanoTime() > 0) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Condition missed its bounded deadline");
    }
    private void idle(NearCacheInvalidationMode configured, boolean denyTracking) throws Exception {
        URI uri = URI.create(System.getProperty("cachedb.tracking.redis.uri", RedisTestEndpoint.uri()));
        var client = DefaultJedisClientConfig.builder().connectionTimeoutMillis(500)
                .socketTimeoutMillis(15000).blockingSocketTimeoutMillis(15000)
                .user(JedisURIHelper.getUser(uri)).password(JedisURIHelper.getPassword(uri))
                .database(JedisURIHelper.getDBIndex(uri)).ssl(JedisURIHelper.isRedisSSLScheme(uri))
                .protocol(RedisProtocol.RESP2).build();
        var factory = new ObservedFactory(new HostAndPort(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort()),
                client, denyTracking);
        var defaults = NearCacheInvalidationConfig.defaults();
        var settings = new NearCacheInvalidationConfig(configured, true,
                defaults.maxMasters(), defaults.maxNodes(), defaults.maxNamespaces(),
                defaults.ioTimeoutMillis(), defaults.heartbeatMillis(), defaults.healthTimeoutMillis(),
                defaults.maxAttempts(), defaults.initialBackoffMillis(), defaults.maxBackoffMillis(), 1000);
        try (var pooled = new JedisPooled(uri)) {
            var operations = new RedisTrackingTestOperations(new JedisPooledOperations(pooled), factory);
            assertEquals(0, factory.created.get(), "SDK bootstrap must not enter the observed dedicated factory");
            try (var coordinator = new RedisNearCacheCoordinator(operations,
                    new RedisKeyStrategy("t2-idle-" + UUID.randomUUID()), settings)) {
                var store = coordinator.register("rows", new NearCachePolicy(32, 10, 1048576));
                await(coordinator::connected, 8000);
                var expected = configured == NearCacheInvalidationMode.SERVER_TRACKING
                        ? NearCacheInvalidationMode.SERVER_TRACKING : NearCacheInvalidationMode.PUBSUB;
                assertEquals(expected, coordinator.invalidationMode());
                assertEquals(denyTracking, factory.denied.get() > 0, "AUTO fallback uses explicit command-failure injection");
                int created = factory.created.get(), destroyed = factory.destroyed.get(), denied = factory.denied.get();
                long clears = store.capture("idle").clearEpoch(), flushes = coordinator.flushAllEvents();
                var ids = coordinator.trackingClientIds();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(32);
                while (until - System.nanoTime() > 0) {
                    assertTrue(coordinator.connected(), coordinator::invalidationFailure);
                    assertEquals(expected, coordinator.invalidationMode());
                    assertEquals(expected == NearCacheInvalidationMode.SERVER_TRACKING, coordinator.trackingReady());
                    assertEquals(created, factory.created.get(), "Healthy idle generation opened another socket");
                    assertEquals(destroyed, factory.destroyed.get(), "Healthy idle generation retired a socket");
                    assertEquals(denied, factory.denied.get(), "Healthy fallback must not periodically retry/retire");
                    assertEquals(clears, store.capture("idle").clearEpoch());
                    assertEquals(flushes, coordinator.flushAllEvents());
                    assertEquals(ids, coordinator.trackingClientIds());
                    assertEquals(0, pooled.getPool().getNumActive(), "Dedicated heartbeats must never borrow pooled members");
                    assertEquals(0, operations.commandCalls.get(), "Heartbeat I/O escaped the dedicated provider");
                    for (var connection : factory.connections) {
                        assertEquals(0, connection.overlaps.get(), "Concurrent Jedis I/O on the same S or C socket");
                        assertEquals(500, connection.getSoTimeout(), "Finite dedicated timeout must override the legacy 15s setting");
                    }
                    Thread.sleep(20);
                }
            }
        }
        assertEquals(factory.created.get(), factory.destroyed.get(), "Every factory-owned socket must be retired");
        for (var connection : factory.connections) assertEquals(0, connection.overlaps.get());
    }
    private static final class ObservedFactory extends BasePooledObjectFactory<Connection> {
        final HostAndPort endpoint;
        final JedisClientConfig client;
        final boolean denyTracking;
        final AtomicInteger created = new AtomicInteger(), destroyed = new AtomicInteger(), denied = new AtomicInteger();
        final List<ObservedConnection> connections = new CopyOnWriteArrayList<>();
        ObservedFactory(HostAndPort endpoint, JedisClientConfig client, boolean denyTracking) {
            this.endpoint = endpoint; this.client = client; this.denyTracking = denyTracking;
        }
        @Override public Connection create() {
            assertTrue(Thread.currentThread().isVirtual(), "Dedicated factory must run on its virtual-thread owner");
            if (created.incrementAndGet() > 64) throw new IllegalStateException("Idle fixture connection budget exceeded");
            var connection = new ObservedConnection(endpoint, client, this);
            connections.add(connection);
            return connection;
        }
        @Override public PooledObject<Connection> wrap(Connection connection) { return new DefaultPooledObject<>(connection); }
        @Override public void destroyObject(PooledObject<Connection> pooled) {
            assertTrue(Thread.currentThread().isVirtual(), "Dedicated destruction must run on its I/O owner");
            pooled.getObject().close(); destroyed.incrementAndGet();
        }
    }
    private static final class ObservedConnection extends Connection {
        final AtomicInteger inIo = new AtomicInteger(), overlaps = new AtomicInteger();
        final ObservedFactory factory;
        boolean armed;
        ObservedConnection(HostAndPort endpoint, JedisClientConfig client, ObservedFactory factory) {
            super(endpoint, client); this.factory = factory; armed = true;
        }
        private <T> T observe(Supplier<T> io) {
            if (!armed) return io.get(); // Super-constructor negotiation precedes field initialization.
            assertTrue(Thread.currentThread().isVirtual(), "Dedicated I/O must run on a virtual-thread owner");
            if (inIo.incrementAndGet() != 1) overlaps.incrementAndGet();
            try { return io.get(); } finally { inIo.decrementAndGet(); }
        }
        @Override public void sendCommand(ProtocolCommand command, String... arguments) {
            observe(() -> {
                if (armed && factory.denyTracking && command == Command.CLIENT && arguments.length > 0
                        && "TRACKING".equals(arguments[0])) {
                    factory.denied.incrementAndGet();
                    throw new JedisDataException("Tracking disabled by idle-fallback fixture");
                }
                super.sendCommand(command, arguments); return null;
            });
        }
        @Override public void sendCommand(ProtocolCommand command) {
            observe(() -> { super.sendCommand(command); return null; });
        }
        @Override public void setTimeoutInfinite() {
            throw new AssertionError("Dedicated heartbeats must not switch to the legacy blocking timeout");
        }
        @Override public Object getOne() { return observe(super::getOne); }
        @Override public Object getUnflushedObject() { return observe(super::getUnflushedObject); }
        @Override public List<Object> getMany(int count) { return observe(() -> super.getMany(count)); }
        @Override public void close() { observe(() -> { super.close(); return null; }); }
    }
}

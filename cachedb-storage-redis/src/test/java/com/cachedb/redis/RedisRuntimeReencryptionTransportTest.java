package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisPooled;

/** Deterministic custom-factory ownership tests; no socket, server, or production executor. */
@Timeout(10)
class RedisRuntimeReencryptionTransportTest {
    @Test void admissionOnlyCloseDoesNotReturnOrDisconnectAnInFlightReadBeforeItsOwnerExits() throws Exception {
        var connection = new ControlledConnection();
        connection.holdRead = true;
        var factory = new ControlledFactory(connection);
        try (var client = new JedisPooled(factory, poolConfig());
                var transport = new RedisRuntimeReencryptionTransport(JedisPooledOperations.of(client));
                var executor = Executors.newSingleThreadExecutor()) {
            Future<Object> physical = executor.submit(() -> {
                try (var session = transport.connect()) {
                    return session.call(BoundedRedisWire.Limits.entries(), "EVAL", "return 'ok'", "0").value();
                }
            });
            try {
                assertTrue(connection.atRead.await(2, TimeUnit.SECONDS));
                assertEquals(2000, connection.timeout);
                assertEquals(1, client.getPool().getNumActive());
                assertCode(BoundedRedisWire.Code.BUSY, transport::connect);
                transport.close();
                assertFalse(physical.isDone());
                assertEquals(1, client.getPool().getNumActive());
                assertEquals(0, client.getPool().getNumIdle());
                assertEquals(0, factory.destroyed.get());
                assertEquals(0, connection.disconnects.get());
                assertCode(BoundedRedisWire.Code.IO_FAILURE, transport::connect);
                connection.releaseRead.countDown();
                // Admission close does not pretend that the already-sent command never completed.
                assertEquals("ok", physical.get(2, TimeUnit.SECONDS));
                assertEquals(0, client.getPool().getNumActive());
                assertEquals(1, client.getPool().getNumIdle());
                assertEquals(7000, connection.timeout);
                assertEquals(0, factory.destroyed.get());
            } finally { connection.releaseRead.countDown(); }
        }
    }

    @Test void admissionCloseDuringCustomFactoryCreationDoesNotInventASocketDeadlineOrEarlyOwnershipRelease() throws Exception {
        var connection = new ControlledConnection();
        var bootstrap = new ControlledConnection();
        var factory = new ControlledFactory(bootstrap);
        try (var client = new JedisPooled(factory, poolConfig());
                var transport = new RedisRuntimeReencryptionTransport(JedisPooledOperations.of(client));
                var executor = Executors.newSingleThreadExecutor()) {
            // Jedis construction eagerly borrows; arm only the subsequent physical acquisition.
            try (var session = transport.connect()) {
                assertEquals("ok", session.call(BoundedRedisWire.Limits.entries(), "EVAL", "return 'ok'", "0").value());
            }
            assertEquals(0, client.getPool().getNumActive());
            assertEquals(1, client.getPool().getNumIdle());
            client.getPool().clear();
            assertEquals(1, factory.destroyed.get());
            assertEquals(1, bootstrap.disconnects.get());
            factory.armCreation(connection);
            Future<?> physical = executor.submit(() -> {
                try (var ignored = transport.connect()) { fail("Closed acquisition was accepted"); }
            });
            try {
                assertTrue(factory.atCreate.await(2, TimeUnit.SECONDS));
                assertCode(BoundedRedisWire.Code.BUSY, transport::connect);
                transport.close();
                assertFalse(physical.isDone());
                assertEquals(1, factory.inCreate.get());
                assertEquals(1, factory.destroyed.get());
                assertEquals(0, connection.disconnects.get());
                assertCode(BoundedRedisWire.Code.IO_FAILURE, transport::connect);
                factory.releaseCreate.countDown();
                var error = assertThrows(ExecutionException.class, () -> physical.get(2, TimeUnit.SECONDS));
                var safe = assertInstanceOf(BoundedRedisWire.Failure.class, error.getCause());
                assertEquals(BoundedRedisWire.Code.IO_FAILURE, safe.code());
                assertEquals(0, factory.inCreate.get());
                assertEquals(0, client.getPool().getNumActive());
                assertEquals(0, client.getPool().getNumIdle());
                assertEquals(2, factory.destroyed.get());
                assertEquals(1, connection.disconnects.get());
            } finally { factory.releaseCreate.countDown(); }
        }
    }

    @Test void errorAfterPartialSendNeverReturnsPotentiallyDirtyConnectionAsHealthy() {
        dirtyError(true);
    }

    @Test void errorDuringReplyNeverReturnsPotentiallyDirtyConnectionAsHealthy() {
        dirtyError(false);
    }

    private void dirtyError(boolean send) {
        var connection = new ControlledConnection();
        connection.errorOnSend = send; connection.errorOnRead = !send;
        var factory = new ControlledFactory(connection);
        try (var client = new JedisPooled(factory, poolConfig());
                var transport = new RedisRuntimeReencryptionTransport(JedisPooledOperations.of(client))) {
            assertSame(connection.fatal, assertThrows(AssertionError.class, () -> {
                try (var session = transport.connect()) {
                    session.call(BoundedRedisWire.Limits.entries(), "EVAL", "return 'ok'", "0");
                }
            }));
            assertEquals(0, client.getPool().getNumActive());
            assertEquals(0, client.getPool().getNumIdle());
            assertEquals(1, factory.destroyed.get());
            assertEquals(1, connection.disconnects.get());
        }
    }

    private static void assertCode(BoundedRedisWire.Code code, Runnable action) {
        assertEquals(code, assertThrows(BoundedRedisWire.Failure.class, action::run).code());
    }

    private static GenericObjectPoolConfig<Connection> poolConfig() {
        var config = new GenericObjectPoolConfig<Connection>();
        config.setMaxTotal(1); config.setMaxIdle(1); config.setJmxEnabled(false);
        config.setMaxWait(Duration.ofSeconds(1));
        return config;
    }

    private static final class ControlledFactory extends BasePooledObjectFactory<Connection> {
        ControlledConnection connection;
        final CountDownLatch atCreate = new CountDownLatch(1), releaseCreate = new CountDownLatch(1);
        final AtomicInteger destroyed = new AtomicInteger(), inCreate = new AtomicInteger();
        boolean holdCreate;
        ControlledFactory(ControlledConnection connection) { this.connection = connection; }
        void armCreation(ControlledConnection connection) { this.connection = connection; holdCreate = true; }
        public Connection create() {
            inCreate.incrementAndGet();
            try {
                if (holdCreate) { atCreate.countDown(); await(releaseCreate); }
                return connection;
            } finally { inCreate.decrementAndGet(); }
        }
        public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
        @Override public void destroyObject(PooledObject<Connection> value) {
            destroyed.incrementAndGet(); value.getObject().disconnect();
        }
    }

    private static final class ControlledConnection extends Connection {
        final CountDownLatch atRead = new CountDownLatch(1), releaseRead = new CountDownLatch(1);
        final AtomicInteger disconnects = new AtomicInteger();
        final AssertionError fatal = new AssertionError("Injected partial I/O failure");
        volatile int timeout = 7000;
        boolean holdRead, errorOnSend, errorOnRead;
        @Override public boolean isConnected() { return disconnects.get() == 0; }
        @Override public int getSoTimeout() { return timeout; }
        @Override public void setSoTimeout(int timeout) { this.timeout = timeout; }
        @Override public void sendCommand(CommandArguments arguments) {
            if (errorOnSend) throw fatal;
        }
        @Override public Object getOne() {
            if (errorOnRead) throw fatal;
            if (holdRead) { atRead.countDown(); await(releaseRead); }
            return "$2\r\nok\r\n".getBytes(StandardCharsets.US_ASCII);
        }
        @Override public void disconnect() { disconnects.incrementAndGet(); }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Bounded fixture latch timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError("Fixture interrupted", interrupted);
        }
    }
}

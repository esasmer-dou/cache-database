package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.config.*;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.*;

/** Real packet blackholes, not CLIENT KILL. Plaintext disposable Redis fixture only. */
@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisTrackingBlackholeTest {
    private static void await(BooleanSupplier condition, long millis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && end - System.nanoTime() > 0) Thread.sleep(2);
        assertTrue(condition.getAsBoolean());
    }
    @Test void subscriptionBlackholeExpiresReadinessEvenWhenControlStillAnswers() throws Exception { blackhole(false); }
    @Test void controlBlackholeExpiresReadinessEvenWhenSubscriptionStillAnswers() throws Exception { blackhole(true); }

    private void blackhole(boolean control) throws Exception {
        String host = System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1");
        int port = Integer.getInteger("cachedb.snapshot.redis.port", 16383);
        try (var c = new Proxy(host, port); var s = new Proxy(host, port)) {
            var created = new AtomicInteger();
            var clientConfig = DefaultJedisClientConfig.builder().connectionTimeoutMillis(500).socketTimeoutMillis(500).build();
            var factory = new BasePooledObjectFactory<Connection>() {
                public Connection create() {
                    assertTrue(Thread.currentThread().isVirtual(), "Proxy socket creation must remain owner-thread I/O");
                    int index = created.getAndIncrement();
                    if (index >= 32) throw new IllegalStateException("Control fixture connection budget exceeded");
                    return new Connection(new HostAndPort("127.0.0.1", (index & 1) == 0 ? c.port() : s.port()), clientConfig);
                }
                public PooledObject<Connection> wrap(Connection connection) { return new DefaultPooledObject<>(connection); }
            };
            try (var pooled = new JedisPooled(RedisTestEndpoint.uri())) {
                var operations = new RedisTrackingTestOperations(new JedisPooledOperations(pooled), factory);
                assertEquals(0, created.get(), "Fixture bootstrap must not consume C/S proxy assignments");
                try (var coordinator = new RedisNearCacheCoordinator(operations,
                        new RedisKeyStrategy("t2-blackhole-" + UUID.randomUUID()), strictOptOut())) {
                    var store = coordinator.register("rows", new NearCachePolicy(32, 10, 1048576));
                    await(coordinator::trackingReady, 8000);
                    store.put("id", "cached", -1, store.capture("id"));
                    assertEquals("cached", store.get("id"));
                    (control ? c : s).blackhole.set(true);
                    await(() -> !coordinator.trackingReady(), 1500);
                    long noFallbackUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                    while (noFallbackUntil - System.nanoTime() > 0) {
                        assertFalse(coordinator.connected(), "Legacy opt-out must never admit a PUBSUB fallback");
                        assertEquals(NearCacheInvalidationMode.SERVER_TRACKING, coordinator.invalidationMode());
                        Thread.sleep(5);
                    }
                    assertNull(store.get("id"), "Health expiry must clear before admitting another L1 read");
                    assertTrue(coordinator.publishLegacyInvalidations(), "Unhealthy tracking cannot suppress compatibility publication");
                    assertEquals(0, pooled.getPool().getNumActive());
                    assertEquals(0, operations.commandCalls.get(), "Blackhole I/O must stay on dedicated sockets");
                }
            }
        }
    }
    private static NearCacheInvalidationConfig strictOptOut() {
        var defaults = NearCacheInvalidationConfig.defaults();
        return new NearCacheInvalidationConfig(NearCacheInvalidationMode.SERVER_TRACKING, false,
                defaults.maxMasters(), defaults.maxNodes(), defaults.maxNamespaces(),
                defaults.ioTimeoutMillis(), defaults.heartbeatMillis(), defaults.healthTimeoutMillis(),
                defaults.maxAttempts(), defaults.initialBackoffMillis(), defaults.maxBackoffMillis(), defaults.retryCooldownMillis());
    }
    private static final class Proxy implements AutoCloseable {
        final AtomicBoolean blackhole = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger accepted = new AtomicInteger();
        final Semaphore clients = new Semaphore(8);
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final CopyOnWriteArrayList<Thread> workers = new CopyOnWriteArrayList<>();
        final ServerSocket server;
        final Thread acceptor;
        final long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        final String host;
        final int targetPort;
        Proxy(String host, int targetPort) throws IOException {
            this.host = host; this.targetPort = targetPort;
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(100);
            acceptor = Thread.ofVirtual().inheritInheritableThreadLocals(false).start(this::accept);
        }
        int port() { return server.getLocalPort(); }
        private void accept() {
            while (!closed.get() && expires - System.nanoTime() > 0) {
                try {
                    Socket inbound = server.accept();
                    if (accepted.incrementAndGet() > 32) { inbound.close(); return; }
                    if (!clients.tryAcquire()) { inbound.close(); continue; }
                    sockets.add(inbound);
                    var worker = Thread.ofVirtual().inheritInheritableThreadLocals(false).unstarted(() -> bridge(inbound));
                    workers.add(worker); worker.start();
                } catch (SocketTimeoutException tick) {
                    // Poll only the fixed fixture lifetime.
                } catch (IOException stopped) { if (!closed.get()) closed.set(true); return; }
            }
        }
        private void bridge(Socket inbound) {
            Socket outbound = new Socket();
            sockets.add(inbound); sockets.add(outbound);
            Thread reverse = null;
            try {
                if (closed.get()) return;
                outbound.connect(new InetSocketAddress(host, targetPort), 500);
                inbound.setSoTimeout(100); outbound.setSoTimeout(100);
                reverse = Thread.ofVirtual().inheritInheritableThreadLocals(false)
                        .unstarted(() -> copy(outbound, inbound));
                workers.add(reverse); reverse.start();
                copy(inbound, outbound);
            } catch (IOException stopped) {
                // Closing a proxy is an expected fixture cancellation path.
            } finally {
                closeSocket(inbound); closeSocket(outbound); clients.release();
                if (reverse != null) reverse.interrupt();
            }
        }
        private void copy(Socket from, Socket to) {
            byte[] buffer = new byte[8192];
            try {
                InputStream input = from.getInputStream(); OutputStream output = to.getOutputStream();
                while (!closed.get() && expires - System.nanoTime() > 0) {
                    int count;
                    try { count = input.read(buffer); } catch (SocketTimeoutException tick) { continue; }
                    if (count < 0) return;
                    if (!blackhole.get()) { output.write(buffer, 0, count); output.flush(); }
                }
            } catch (IOException stopped) {
                // A real socket close/timeout is not mistaken for a successful forwarding operation.
            } finally { closeSocket(from); closeSocket(to); }
        }
        private void closeSocket(Socket socket) {
            sockets.remove(socket);
            try { socket.close(); } catch (IOException ignored) { /* Test teardown, already fail-closed. */ }
        }
        @Override public void close() throws Exception {
            closed.set(true); server.close();
            acceptor.interrupt();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            acceptor.join(Duration.ofSeconds(1));
            for (Socket socket : sockets) closeSocket(socket);
            for (Thread worker : workers) {
                worker.interrupt();
                long remaining = end - System.nanoTime();
                if (remaining > 0) worker.join(Duration.ofNanos(remaining));
            }
            long remaining = end - System.nanoTime();
            if (remaining > 0) acceptor.join(Duration.ofNanos(remaining));
            assertFalse(acceptor.isAlive());
            assertTrue(workers.stream().noneMatch(Thread::isAlive), "Bounded proxy teardown left a worker");
        }
    }
}

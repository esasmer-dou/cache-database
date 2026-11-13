package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.redis.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.pool2.*;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.*;

@Timeout(15)
class CacheDbCacheCloseSocketTest {
    @Test void idlePubsubBlackholeIsCancelledAndDestroyedByTheOwner() throws Exception {
        idlePubsub(false);
    }

    @Test void idlePubsubOrderlySocketAlsoConfirmsPeerEof() throws Exception {
        idlePubsub(true);
    }

    private void idlePubsub(boolean orderly) throws Exception {
        try (var server = new Blackhole(false)) {
            var sockets = new SocketWitness(server.port(), null, orderly);
            var destroyed = new AtomicInteger();
            var factory = new BasePooledObjectFactory<Connection>() {
                public Connection create() {
                    assertTrue(Thread.currentThread().isVirtual());
                    return new Connection(sockets);
                }
                public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
                @Override public void destroyObject(PooledObject<Connection> value) {
                    assertSame(sockets.owner, Thread.currentThread(), "Only the subscription owner destroys its connection");
                    value.getObject().close(); destroyed.incrementAndGet();
                }
            };
            var operations = operations(factory);
            var coordinator = new CacheDbCacheInvalidationCoordinator(operations, "socket-close");
            try {
                coordinator.register("cache", CacheDbCacheRedisTest.slowNear(false));
                coordinator.freezeAndStartIfNearEnabled();
                CacheDbCacheRedisFixture.await(coordinator::connected);
                assertTrue(server.received.await(2, TimeUnit.SECONDS), () -> String.valueOf(server.failure));
                coordinator.close();
                assertFalse(coordinator.listenerAlive());
                assertFalse(coordinator.cleanupFailed());
                assertEquals(1, destroyed.get());
                sockets.assertClosed();
                server.assertPeerClosed(orderly);
                verify(operations, never()).commands();
            } finally { coordinator.close(); }
        }
    }

    @Test void originalFiniteAuthReadDeadlineIsNotReplacedByDedicatedSubscriptionDefaults() throws Exception {
        authDeadline(false);
    }

    @Test void finiteAuthDeadlineAlsoClosesAnOrderlySocketWithPeerEof() throws Exception {
        authDeadline(true);
    }

    private void authDeadline(boolean orderly) throws Exception {
        try (var server = new Blackhole(true);
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(300)
                    .socketTimeoutMillis(300).blockingSocketTimeoutMillis(500).password("fixture-only").build();
            var sockets = new SocketWitness(server.port(), config, orderly);
            var operations = operations(new ConnectionFactory(sockets, config));
            var task = tasks.submit(() -> CacheDbCacheSubscription.listen(operations, new JedisPubSub() {}, "auth-deadline"));
            assertTrue(server.received.await(2, TimeUnit.SECONDS), () -> String.valueOf(server.failure));
            var failed = assertThrows(ExecutionException.class, () -> task.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failed.getCause());
            assertInstanceOf(SocketTimeoutException.class, failed.getCause().getCause().getCause());
            assertEquals(300, sockets.readTimeout, "AUTH must retain the original finite read deadline");
            sockets.assertClosed();
            server.assertPeerClosed(orderly);
            verify(operations, never()).commands();
        }
    }

    private static final class SocketWitness implements JedisSocketFactory {
        final DefaultJedisSocketFactory delegate;
        final boolean orderly;
        volatile Socket socket;
        volatile Thread owner;
        volatile int readTimeout;
        SocketWitness(int port, JedisClientConfig config, boolean orderly) {
            delegate = new DefaultJedisSocketFactory(new HostAndPort("127.0.0.1", port), config);
            this.orderly = orderly;
        }
        public Socket createSocket() {
            assertTrue(Thread.currentThread().isVirtual());
            assertNull(socket, "Cancellation must not reconnect the owned socket");
            owner = Thread.currentThread();
            Socket created = delegate.createSocket();
            socket = created;
            try {
                readTimeout = created.getSoTimeout();
                assertEquals(0, created.getSoLinger(), "Observe the native factory's abortive-close default");
                // Only the additional FIN cases alter linger; all timeouts and AUTH settings stay native.
                if (orderly) created.setSoLinger(false, 0);
                return created;
            } catch (IOException | RuntimeException | Error failure) {
                try { created.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw new AssertionError(failure);
            }
        }
        void assertClosed() {
            assertNotNull(socket, "A physical socket must have been created");
            assertTrue(socket.isClosed(), "The owned physical socket remained open");
        }
    }

    private static RedisClientOperations operations(PooledObjectFactory<Connection> factory) {
        var operations = mock(RedisClientOperations.class, withSettings().extraInterfaces(RedisTrackingConnectionProvider.class));
        when(((RedisTrackingConnectionProvider) operations).trackingNodes(64))
                .thenReturn(List.of(new RedisTrackingNode("blackhole", factory)));
        return operations;
    }

    /** A single bounded loopback connection: ACK SUBSCRIBE or deliberately withhold AUTH replies. */
    private static final class Blackhole implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final CountDownLatch received = new CountDownLatch(1), peerClosed = new CountDownLatch(1);
        final AtomicBoolean closing = new AtomicBoolean();
        final Thread worker;
        volatile Socket peer;
        volatile Throwable failure;
        volatile String termination;
        Blackhole(boolean auth) throws IOException {
            server.setSoTimeout(5000);
            worker = Thread.ofVirtual().start(() -> {
                boolean draining = false;
                try (Socket socket = server.accept()) {
                    peer = socket; socket.setSoTimeout(5000);
                    var input = new BufferedInputStream(socket.getInputStream());
                    int arguments = Integer.parseInt(line(input).substring(1));
                    if (arguments < 1 || arguments > 16) throw new IOException("Fixture argument budget");
                    String[] values = new String[arguments];
                    for (int i = 0; i < arguments; i++) {
                        int size = Integer.parseInt(line(input).substring(1));
                        if (size < 0 || size > 4096) throw new IOException("Fixture byte budget");
                        byte[] bytes = input.readNBytes(size);
                        if (bytes.length != size || input.read() != 13 || input.read() != 10)
                            throw new EOFException("Incomplete bounded command");
                        values[i] = new String(bytes, StandardCharsets.UTF_8);
                    }
                    if (!auth) {
                        if (arguments != 2 || !values[0].equalsIgnoreCase("SUBSCRIBE"))
                            throw new IOException("Expected SUBSCRIBE");
                        byte[] channel = values[1].getBytes(StandardCharsets.UTF_8);
                        var output = socket.getOutputStream();
                        output.write(("*3\r\n$9\r\nsubscribe\r\n$" + channel.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                        output.write(channel); output.write("\r\n:1\r\n".getBytes(StandardCharsets.US_ASCII)); output.flush();
                    } else if (arguments != 2 || !values[0].equalsIgnoreCase("AUTH") || !values[1].equals("fixture-only"))
                        throw new IOException("Expected bounded AUTH");
                    draining = true;
                    received.countDown();
                    int drained = 0;
                    while (input.read() >= 0) if (++drained > 8192) throw new IOException("Fixture drain budget");
                    confirmPeerClose("EOF");
                } catch (SocketException error) {
                    // Jedis' default SO_LINGER=0 produces RST, not FIN/EOF. No other I/O error proves close.
                    if (!closing.get()) {
                        if (draining && isConnectionReset(error)) confirmPeerClose("RESET");
                        else failure = error;
                    }
                } catch (Throwable error) { if (!closing.get()) failure = error; }
            });
        }
        private static boolean isConnectionReset(SocketException error) {
            return "Connection reset".equals(error.getMessage()) || "Connection reset by peer".equals(error.getMessage());
        }
        private void confirmPeerClose(String kind) {
            if (!closing.get()) { termination = kind; peerClosed.countDown(); }
        }
        void assertPeerClosed(boolean orderly) throws InterruptedException {
            assertTrue(peerClosed.await(2, TimeUnit.SECONDS), () -> "Peer did not confirm physical close; failure=" + failure);
            assertFalse(closing.get(), "Fixture teardown cannot prove client cleanup");
            assertNull(failure, () -> String.valueOf(failure));
            if (orderly) assertEquals("EOF", termination, "The FIN fixture must observe actual peer EOF");
            else assertTrue("EOF".equals(termination) || "RESET".equals(termination));
        }
        private static String line(InputStream input) throws IOException {
            var text = new StringBuilder();
            for (int i = 0; i < 8192; i++) {
                int c = input.read(); if (c < 0) throw new EOFException();
                if (c == 13) { if (input.read() != 10) throw new IOException("Invalid CRLF"); return text.toString(); }
                text.append((char) c);
            }
            throw new IOException("Fixture line budget");
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception {
            closing.set(true);
            server.close();
            Socket socket = peer; if (socket != null) socket.close();
            worker.interrupt();
            assertTrue(worker.join(Duration.ofSeconds(2)), "Loopback fixture owner did not stop");
            if (failure != null) throw new AssertionError(failure);
        }
    }
}

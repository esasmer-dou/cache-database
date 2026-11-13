package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.config.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.commons.pool2.PooledObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.*;

/** Local RESP barrier, not a Redis acceptance test. No external services or unbounded fixture threads. */
@Timeout(10)
class RedisTrackingStartupSocketCancellationTest {
    @Test void closeInterruptsRealSocketAuthInsideMakeObjectBeforeItReturns() throws Exception { cancel(true); }
    @Test void closeInterruptsRealClientTrackingReplyAndBothOwnersDestroyTheirSockets() throws Exception { cancel(false); }

    private void cancel(boolean stopAtAuth) throws Exception {
        try (var server = new RegistrationServer(stopAtAuth)) {
            var settings = DefaultJedisClientConfig.builder().password("local-fixture")
                    .connectionTimeoutMillis(500).socketTimeoutMillis(5000).build();
            var factory = new ObservedFactory(new HostAndPort("127.0.0.1", server.port()), settings);
            // This independent URI client is borrowed; only the provider below observes S/C sockets.
            try (var pooled = new JedisPooled(RedisTestEndpoint.uri())) {
                var operations = new RedisTrackingTestOperations(new JedisPooledOperations(pooled), factory);
                assertEquals(0, factory.attempted.get(), "No factory I/O during fixture construction");
                var store = new RedisNearCacheCoordinator.Store(new NearCachePolicy(16, 10, 1048576));
                var coordinator = new RedisTrackingCoordinator(operations, new RedisKeyStrategy("socket-stop"),
                        NearCacheInvalidationConfig.defaults().withMode(NearCacheInvalidationMode.SERVER_TRACKING),
                        () -> Map.of("rows", store), store::clear);
                Thread supervisor = Thread.ofVirtual().inheritInheritableThreadLocals(false).start(coordinator::run);
                try {
                    assertTrue(server.atBarrier.await(2, TimeUnit.SECONDS), "Real socket missed registration barrier");
                    long started = System.nanoTime();
                    server.stopping.set(true);
                    coordinator.close();
                    assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(2500));
                    assertTrue(supervisor.join(Duration.ofMillis(100)));
                    assertFalse(coordinator.ready());
                    assertEquals(0, factory.inFlight.get(), "makeObject retained an initialization owner");
                    assertTrue(factory.owners.stream().noneMatch(Thread::isAlive));
                    assertEquals(factory.returned.get(), factory.destroyed.get(), "Returned factory socket was not destroyed");
                    assertEquals(stopAtAuth ? 0 : 2, factory.returned.get());
                    assertEquals(stopAtAuth ? 1 : 2, factory.attempted.get());
                    assertTrue(server.peerEof.await(500, TimeUnit.MILLISECONDS), "Peer retained a socket after owner close");
                    assertEquals(stopAtAuth ? 1 : 2, server.eofs.get());
                    assertEquals(0, pooled.getPool().getNumActive());
                    assertEquals(0, operations.commandCalls.get(), "Registration must use dedicated sockets only");
                    coordinator.close();
                    assertEquals(stopAtAuth ? 1 : 2, factory.attempted.get(), "Close allowed a late reconnect");
                    assertNull(server.failure.get(), () -> String.valueOf(server.failure.get()));
                } finally { coordinator.close(); supervisor.interrupt(); }
            }
        }
    }
    private static final class ObservedFactory extends ConnectionFactory {
        final AtomicInteger attempted = new AtomicInteger(), returned = new AtomicInteger();
        final AtomicInteger destroyed = new AtomicInteger(), inFlight = new AtomicInteger();
        final Set<Thread> owners = ConcurrentHashMap.newKeySet();
        ObservedFactory(HostAndPort endpoint, JedisClientConfig config) { super(endpoint, config); }
        @Override public PooledObject<Connection> makeObject() throws Exception {
            assertTrue(Thread.currentThread().isVirtual());
            owners.add(Thread.currentThread()); attempted.incrementAndGet(); inFlight.incrementAndGet();
            try {
                var result = super.makeObject();
                returned.incrementAndGet();
                return result;
            } finally { inFlight.decrementAndGet(); }
        }
        @Override public void destroyObject(PooledObject<Connection> owned) throws Exception {
            assertTrue(Thread.currentThread().isVirtual());
            super.destroyObject(owned); destroyed.incrementAndGet();
        }
    }
    private static final class RegistrationServer implements AutoCloseable {
        final boolean stopAtAuth;
        final CountDownLatch atBarrier = new CountDownLatch(1), peerEof;
        final AtomicBoolean closed = new AtomicBoolean(), stopping = new AtomicBoolean();
        final AtomicInteger accepted = new AtomicInteger(), eofs = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final List<Thread> workers = new CopyOnWriteArrayList<>();
        final ServerSocket server;
        final Thread acceptor;
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        RegistrationServer(boolean stopAtAuth) throws IOException {
            this.stopAtAuth = stopAtAuth;
            peerEof = new CountDownLatch(stopAtAuth ? 1 : 2);
            server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(50);
            acceptor = Thread.ofVirtual().inheritInheritableThreadLocals(false).start(this::accept);
        }
        int port() { return server.getLocalPort(); }
        private void accept() {
            while (!closed.get() && until - System.nanoTime() > 0 && accepted.get() < 2) {
                try {
                    Socket socket = server.accept();
                    int id = accepted.incrementAndGet();
                    socket.setSoTimeout(1000); sockets.add(socket);
                    Thread worker = Thread.ofVirtual().inheritInheritableThreadLocals(false).unstarted(() -> serve(socket, id));
                    workers.add(worker); worker.start();
                } catch (SocketTimeoutException tick) {
                    // Fixed fixture lifetime, not an unbounded helper.
                } catch (IOException error) { if (!closed.get()) failure.compareAndSet(null, error); return; }
            }
        }
        private void serve(Socket socket, int id) {
            try {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                for (int commands = 0; commands < 128 && !closed.get() && until - System.nanoTime() > 0; commands++) {
                    List<String> args;
                    try { args = command(input); }
                    catch (SocketTimeoutException tick) { commands--; continue; }
                    if (args == null) { eof(); return; }
                    String operation = args.getFirst();
                    boolean barrier = stopAtAuth && "AUTH".equals(operation)
                            || !stopAtAuth && "CLIENT".equals(operation) && args.size() > 1 && "TRACKING".equals(args.get(1));
                    if (barrier) {
                        socket.setSoTimeout(100);
                        atBarrier.countDown();
                        // Deliberately withhold the reply, retaining a real TCP connection.
                        while (!closed.get() && until - System.nanoTime() > 0) {
                            try {
                                if (input.read() == -1) { eof(); return; }
                                throw new AssertionError("Client wrote beyond the blocked registration reply");
                            } catch (SocketTimeoutException tick) { /* Bounded peer-close polling. */ }
                            catch (SocketException reset) { eof(); return; }
                        }
                        return;
                    }
                    if ("ROLE".equals(operation)) write(output, "*1\r\n$6\r\nmaster\r\n");
                    else if ("CLIENT".equals(operation) && "ID".equals(args.get(1))) write(output, ":" + id + "\r\n");
                    else if ("SUBSCRIBE".equals(operation)) {
                        String channel = args.get(1);
                        write(output, "*3\r\n$9\r\nsubscribe\r\n$" + channel.length() + "\r\n" + channel + "\r\n:1\r\n");
                    } else if ("PING".equals(operation) && args.size() == 2) {
                        String token = args.get(1);
                        write(output, "*2\r\n$4\r\npong\r\n$" + token.length() + "\r\n" + token + "\r\n");
                    } else if ("PING".equals(operation)) write(output, "+PONG\r\n");
                    else write(output, "+OK\r\n"); // AUTH and the SDK's CLIENT SETINFO negotiation.
                }
                if (!closed.get()) throw new AssertionError("RESP fixture lifetime/command budget exceeded");
            } catch (SocketException reset) {
                if (stopping.get()) eof();
                else if (!closed.get()) failure.compareAndSet(null, reset);
            } catch (Throwable error) {
                if (!closed.get()) failure.compareAndSet(null, error);
            } finally {
                sockets.remove(socket);
                try { socket.close(); } catch (IOException ignored) { /* Bounded fixture teardown. */ }
            }
        }
        private void eof() { eofs.incrementAndGet(); peerEof.countDown(); }
        private static void write(OutputStream output, String value) throws IOException {
            output.write(value.getBytes(StandardCharsets.UTF_8)); output.flush();
        }
        private static List<String> command(InputStream input) throws IOException {
            int first = input.read();
            if (first == -1) return null;
            if (first != '*') throw new IOException("Expected RESP array");
            int count = Integer.parseInt(line(input));
            if (count < 1 || count > 32) throw new IOException("RESP command budget");
            var result = new ArrayList<String>(count);
            for (int i = 0; i < count; i++) {
                if (input.read() != '$') throw new IOException("Expected RESP bulk argument");
                int length = Integer.parseInt(line(input));
                if (length < 0 || length > 8192) throw new IOException("RESP argument budget");
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length || input.read() != '\r' || input.read() != '\n') throw new EOFException();
                result.add(new String(bytes, StandardCharsets.UTF_8));
            }
            return result;
        }
        private static String line(InputStream input) throws IOException {
            var value = new StringBuilder();
            for (int i = 0; i < 64; i++) {
                int next = input.read();
                if (next < 0) throw new EOFException();
                if (next == '\r') {
                    if (input.read() != '\n') throw new IOException("Malformed RESP line");
                    return value.toString();
                }
                value.append((char) next);
            }
            throw new IOException("RESP line budget");
        }
        @Override public void close() throws Exception {
            closed.set(true); server.close(); acceptor.interrupt();
            for (Socket socket : sockets) socket.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            for (Thread worker : workers) {
                worker.interrupt();
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) worker.join(Duration.ofNanos(remaining));
            }
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) acceptor.join(Duration.ofNanos(remaining));
            assertFalse(acceptor.isAlive());
            assertTrue(workers.stream().noneMatch(Thread::isAlive), "Bounded RESP fixture left a helper");
        }
    }
}

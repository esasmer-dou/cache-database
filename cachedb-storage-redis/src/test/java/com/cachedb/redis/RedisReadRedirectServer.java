package com.reactor.cachedb.redis;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.util.JedisClusterCRC16;

/** Scripted RESP peers exercise the REAL Jedis cluster executor, not a hand-written redirect imitation. */
final class RedisReadRedirectServer implements AutoCloseable {
    private static final byte[] SCRIPT_BYTES = RedisBoundedPointRead.SCRIPT.getBytes(UTF_8);
    private static final byte[] SCRIPT_SHA = scriptSha();
    enum Fault { NONE, MOVED, ASK, MOVED_LOOP, ASK_LOOP, DISCONNECT, ACL, BUDGET }
    final Node first;
    final Node second;
    final AtomicReference<Node> owner = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    volatile Fault fault = Fault.NONE;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();
    volatile boolean closed;

    private static byte[] scriptSha() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(SCRIPT_BYTES))
                    .getBytes(US_ASCII);
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    RedisReadRedirectServer() throws IOException {
        first = new Node();
        second = new Node();
        owner.set(first);
        first.start();
        second.start();
    }

    JedisClusterOperations connect(int attempts) {
        var pool = new GenericObjectPoolConfig<Connection>();
        pool.setMaxTotal(2);
        pool.setMaxIdle(2);
        pool.setMaxWait(Duration.ofSeconds(1));
        var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(500)
                .socketTimeoutMillis(500).build();
        return new JedisClusterOperations(new JedisCluster(Set.of(first.address()), config,
                attempts, Duration.ofSeconds(2), pool));
    }

    final class Node {
        final ServerSocket listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        final AtomicInteger evals = new AtomicInteger();
        final AtomicInteger asking = new AtomicInteger();
        final AtomicInteger slots = new AtomicInteger();
        Node() throws IOException {}
        HostAndPort address() { return new HostAndPort("127.0.0.1", listener.getLocalPort()); }
        void start() {
            workers.add(Thread.ofVirtual().name("cachedb-redirect-test-accept").start(() -> {
                while (!closed) {
                    try {
                        Socket socket = listener.accept();
                        if (accepted.incrementAndGet() > 32) {
                            socket.close();
                            throw new IOException("Fault fixture exceeded its total connection bound");
                        }
                        socket.setSoTimeout(5000);
                        sockets.add(socket);
                        workers.add(Thread.ofVirtual().name("cachedb-redirect-test-peer")
                                .start(() -> serve(socket)));
                    } catch (IOException problem) {
                        if (!closed) failure.compareAndSet(null, problem);
                        return;
                    }
                }
            }));
        }

        void serve(Socket socket) {
            try (socket; var in = new BufferedInputStream(socket.getInputStream());
                    var out = new BufferedOutputStream(socket.getOutputStream())) {
                boolean asked = false;
                boolean replyFlushed = false;
                while (!closed) {
                    List<byte[]> args = command(in, replyFlushed);
                    replyFlushed = false;
                    if (args == null) return;
                    String name = new String(args.getFirst(), US_ASCII);
                    switch (name) {
                        case "CLIENT", "AUTH", "SELECT", "READONLY" -> out.write("+OK\r\n".getBytes(US_ASCII));
                        case "PING" -> out.write("+PONG\r\n".getBytes(US_ASCII));
                        case "CLUSTER" -> {
                            if (args.size() != 2 || !"SLOTS".equals(new String(args.get(1), US_ASCII)))
                                throw new IOException("Unexpected CLUSTER command");
                            slots.incrementAndGet();
                            Node current = owner.get();
                            reply(out, List.of(List.of(0L, 16383L, List.of("127.0.0.1".getBytes(US_ASCII),
                                    (long) current.address().getPort(), "test-node".getBytes(US_ASCII)))));
                        }
                        case "ASKING" -> {
                            asked = true;
                            asking.incrementAndGet();
                            out.write("+OK\r\n".getBytes(US_ASCII));
                        }
                        case "EVAL", "EVALSHA" -> {
                            evals.incrementAndGet();
                            byte[] expected = "EVAL".equals(name) ? SCRIPT_BYTES : SCRIPT_SHA;
                            if (args.size() < 3 || !Arrays.equals(expected, args.get(1)))
                                throw new IOException("Not the guarded binary script");
                            int count = Integer.parseInt(new String(args.get(2), US_ASCII));
                            if (count < 1 || count > 1024 || args.size() != 4 + 2 * count)
                                throw new IOException("Wrong bounded script argument count");
                            int slot = JedisClusterCRC16.getSlot(args.get(3));
                            Node target = this == first ? second : first;
                            Fault current = fault;
                            boolean moved = current == Fault.MOVED_LOOP || (current == Fault.MOVED && this == first);
                            boolean ask = current == Fault.ASK_LOOP || (current == Fault.ASK && this == first);
                            if (moved || ask) {
                                if (moved) owner.set(target);
                                error(out, (moved ? "MOVED " : "ASK ") + slot + " " + target.address());
                            } else if (current == Fault.DISCONNECT) {
                                return;
                            } else if (current == Fault.ACL) {
                                error(out, "NOPERM scripted ACL denial");
                            } else if (current == Fault.BUDGET) {
                                error(out, "CACHEDB_READ_BUDGET");
                            } else {
                                if (current == Fault.ASK && this == second && !asked)
                                    throw new IOException("Redirect target script did not follow ASKING on the same connection");
                                var values = new ArrayList<Object>();
                                var ttls = new ArrayList<Long>();
                                for (int i = 0; i < count; i++) {
                                    values.add(i % 2 == 0 ? "row-ğ".getBytes(UTF_8) : null);
                                    ttls.add("1".equals(new String(args.get(4 + count + i), US_ASCII)) ? -1L : -3L);
                                }
                                reply(out, List.of(values, ttls));
                            }
                            asked = false; // ASKING is one-shot and connection-local.
                        }
                        default -> throw new IOException("Unexpected command " + name);
                    }
                    out.flush();
                    replyFlushed = true;
                }
            } catch (IOException problem) {
                if (!closed) failure.compareAndSet(null, problem);
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            } finally { sockets.remove(socket); }
        }
    }

    static List<byte[]> command(InputStream in, boolean replyFlushed) throws IOException {
        int first;
        try { first = in.read(); }
        catch (SocketException reset) {
            // Jedis5.2 SO_LINGER(0) closes discovery/pool sockets with RST. Only BETWEEN full exchanges.
            if (replyFlushed && ("Connection reset".equalsIgnoreCase(reset.getMessage())
                    || "Connection reset by peer".equalsIgnoreCase(reset.getMessage()))) return null;
            throw reset;
        }
        String head = line(in, first);
        if (head == null) return null;
        if (!head.startsWith("*")) throw new IOException("Expected RESP array");
        int count = Integer.parseInt(head.substring(1));
        if (count < 1 || count > 2052) throw new IOException("Unbounded test command");
        var values = new ArrayList<byte[]>(count);
        for (int i = 0; i < count; i++) {
            String size = line(in);
            if (size == null || !size.startsWith("$")) throw new IOException("Expected bulk argument");
            int length = Integer.parseInt(size.substring(1));
            if (length < 0 || length > 65536) throw new IOException("Unbounded test argument");
            byte[] value = in.readNBytes(length);
            if (value.length != length || in.read() != '\r' || in.read() != '\n') throw new EOFException();
            values.add(value);
        }
        return values;
    }
    private static String line(InputStream in) throws IOException { return line(in, in.read()); }
    private static String line(InputStream in, int first) throws IOException {
        var buffer = new StringBuilder();
        for (int value = first; value != -1; value = in.read()) {
            if (value == '\r') {
                if (in.read() != '\n') throw new IOException("Invalid RESP line ending");
                return buffer.toString();
            }
            if (buffer.length() >= 512) throw new IOException("Unbounded test line");
            buffer.append((char) value);
        }
        if (buffer.isEmpty()) return null;
        throw new EOFException();
    }
    private static void error(OutputStream out, String value) throws IOException {
        out.write(("-" + value + "\r\n").getBytes(US_ASCII));
    }
    private static void reply(OutputStream out, Object value) throws IOException {
        if (value == null) out.write("$-1\r\n".getBytes(US_ASCII));
        else if (value instanceof byte[] bytes) {
            out.write(("$" + bytes.length + "\r\n").getBytes(US_ASCII));
            out.write(bytes);
            out.write("\r\n".getBytes(US_ASCII));
        } else if (value instanceof Long number) {
            out.write((":" + number + "\r\n").getBytes(US_ASCII));
        } else if (value instanceof List<?> list) {
            out.write(("*" + list.size() + "\r\n").getBytes(US_ASCII));
            for (Object child : list) reply(out, child);
        } else throw new IOException("Unsupported scripted reply");
    }

    void assertHealthy() { org.junit.jupiter.api.Assertions.assertNull(failure.get(), "RESP fixture failure"); }
    @Override public void close() throws Exception {
        closed = true;
        first.listener.close();
        second.listener.close();
        for (Socket socket : sockets) socket.close();
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        for (Thread worker : workers) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) break;
            worker.join(Duration.ofNanos(remaining));
        }
        assertHealthy();
    }
}

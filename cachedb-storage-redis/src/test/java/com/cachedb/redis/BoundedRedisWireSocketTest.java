package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real loopback sockets on the test host; no Redis daemon or shared-server mutation. */
@Timeout(10)
class BoundedRedisWireSocketTest {
    @Test void evalLargerThanOutputBufferRetainsExactRespFraming() throws Exception {
        String script = "return 1\n--" + "x".repeat(8192);
        String frame = "*3\r\n$4\r\nEVAL\r\n$" + script.length() + "\r\n" + script + "\r\n$1\r\n0\r\n";
        try (var listener = listener(); var server = Executors.newSingleThreadExecutor();
                var wire = wire(listener, 2000)) {
            Future<?> exchange = server.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    byte[] received = socket.getInputStream().readNBytes(frame.length());
                    assertArrayEquals(frame.getBytes(StandardCharsets.US_ASCII), received);
                    socket.getOutputStream().write(":1\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
            try (var session = wire.connect()) {
                assertEquals(1L, session.call(BoundedRedisWire.Limits.entries(), "EVAL", script, "0").value());
            }
            exchange.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void absoluteDeadlineTerminatesTrickledReplyAndOwnerRetainsPermitUntilSessionClose() throws Exception {
        try (var listener = listener(); var server = Executors.newSingleThreadExecutor();
                var wire = wire(listener, 180)) {
            Future<?> exchange = server.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    assertEquals(14, socket.getInputStream().readNBytes(14).length);
                    var output = socket.getOutputStream();
                    output.write("$32\r\n".getBytes(StandardCharsets.US_ASCII)); output.flush();
                    for (int i = 0; i < 32; i++) {
                        output.write('x'); output.flush(); Thread.sleep(35);
                    }
                } catch (SocketException closedByDeadline) {
                    // The peer's finite absolute deadline closes its owned socket.
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            });
            try (var session = wire.connect()) {
                long started = System.nanoTime();
                assertEquals(BoundedRedisWire.Code.IO_FAILURE, assertThrows(BoundedRedisWire.Failure.class,
                        () -> session.call(BoundedRedisWire.Limits.entries(), "PING")).code());
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
                assertEquals(BoundedRedisWire.Code.BUSY, assertThrows(BoundedRedisWire.Failure.class, wire::connect).code());
            }
            // The permit can be acquired again only after the physical session owner's close.
            try (var reopened = wire.connect()) { assertNotNull(reopened); }
            exchange.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void explicitWireCloseUnblocksPhysicalReadWithoutCrossThreadSessionRelease() throws Exception {
        var commandRead = new CountDownLatch(1);
        var stopServer = new CountDownLatch(1);
        try (var listener = listener(); var server = Executors.newSingleThreadExecutor();
                var owner = Executors.newSingleThreadExecutor(); var wire = wire(listener, 2000)) {
            Future<?> exchange = server.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    assertEquals(14, socket.getInputStream().readNBytes(14).length);
                    commandRead.countDown();
                    assertTrue(stopServer.await(3, TimeUnit.SECONDS));
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            });
            Future<?> physical = owner.submit(() -> {
                try (var session = wire.connect()) { session.call(BoundedRedisWire.Limits.entries(), "PING"); }
            });
            try {
                assertTrue(commandRead.await(2, TimeUnit.SECONDS));
                wire.close();
                var failure = assertThrows(ExecutionException.class, () -> physical.get(2, TimeUnit.SECONDS));
                assertEquals(BoundedRedisWire.Code.IO_FAILURE,
                        assertInstanceOf(BoundedRedisWire.Failure.class, failure.getCause()).code());
                assertEquals(BoundedRedisWire.Code.IO_FAILURE, assertThrows(BoundedRedisWire.Failure.class, wire::connect).code());
            } finally { stopServer.countDown(); }
            exchange.get(2, TimeUnit.SECONDS);
        }
    }

    private static ServerSocket listener() throws IOException {
        var listener = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(3000);
        return listener;
    }
    private static BoundedRedisWire wire(ServerSocket listener, int readMillis) {
        return new BoundedRedisWire(new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort()),
                null, null, null, null, 0, 1000, readMillis, 1);
    }
}

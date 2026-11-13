package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RedisReadRedirectFramingTest {
    private static InputStream failureAfter(String prefix, IOException failure) {
        return new InputStream() {
            final byte[] bytes = prefix.getBytes(StandardCharsets.US_ASCII);
            int position;
            @Override public int read() throws IOException {
                if (position < bytes.length) return bytes[position++] & 255;
                throw failure;
            }
        };
    }

    @Test void sdkResetAtNewFrameBoundaryAfterFlushedReplyIsARecognizedClose() throws Exception {
        assertNull(RedisReadRedirectServer.command(failureAfter("", new SocketException("Connection reset")), true));
        assertNull(RedisReadRedirectServer.command(
                failureAfter("", new SocketException("Connection reset by peer")), true));
    }

    @Test void resetWithoutAnyCompletedExchangeIsNotSilentlyAccepted() {
        var failure = new SocketException("Connection reset");
        assertSame(failure, assertThrows(SocketException.class,
                () -> RedisReadRedirectServer.command(failureAfter("", failure), false)));
    }

    @Test void resetInsideArrayHeaderFailsEvenAfterEarlierReplyWasFlushed() {
        var failure = new SocketException("Connection reset");
        assertSame(failure, assertThrows(SocketException.class,
                () -> RedisReadRedirectServer.command(failureAfter("*", failure), true)));
    }

    @Test void resetInsideBulkHeaderFailsEvenAfterEarlierReplyWasFlushed() {
        var failure = new SocketException("Connection reset");
        assertSame(failure, assertThrows(SocketException.class,
                () -> RedisReadRedirectServer.command(failureAfter("*1\r\n$", failure), true)));
    }

    @Test void resetInsideBulkPayloadFailsEvenAfterEarlierReplyWasFlushed() {
        var failure = new SocketException("Connection reset");
        assertSame(failure, assertThrows(SocketException.class,
                () -> RedisReadRedirectServer.command(failureAfter("*1\r\n$4\r\npi", failure), true)));
    }

    @Test void partialFrameEofIsNotMistakenForPoolShutdown() {
        for (String partial : java.util.List.of("*1\r\n$4\r\npi", "*1\r\n$4\r\nping\r", "*1\r\n$")) {
            assertThrows(IOException.class, () -> RedisReadRedirectServer.command(
                    new ByteArrayInputStream(partial.getBytes(StandardCharsets.US_ASCII)), true));
        }
    }

    @Test void timeoutOrOtherSocketFailureAtBoundaryIsStillAnError() {
        for (IOException failure : java.util.List.of(
                new SocketTimeoutException("Read timed out"), new SocketException("Broken pipe"))) {
            assertSame(failure, assertThrows(IOException.class,
                    () -> RedisReadRedirectServer.command(failureAfter("", failure), true)));
        }
    }

    @Test void completeCommandStillParsesAndCleanBoundaryEofIsAllowed() throws Exception {
        var input = new ByteArrayInputStream("*1\r\n$4\r\nping\r\n".getBytes(StandardCharsets.US_ASCII));
        var command = RedisReadRedirectServer.command(input, false);
        assertEquals(1, command.size());
        assertArrayEquals("ping".getBytes(StandardCharsets.US_ASCII), command.getFirst());
        assertNull(RedisReadRedirectServer.command(input, true));
    }
}

package com.reactor.cachedb.core.codec;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.List;

class JsonArrayCodecTest {
    private abstract static class Codec implements JsonArrayCodec<Integer> {
        public Class<Integer> valueType() {
            return Integer.class;
        }

        public List<Integer> decodeArray(String json) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void convenienceEncodingSharesTheNoopAndRetainsCallerOwnershipAndInterruption()
            throws Exception {
        class Recording extends Codec {
            Runnable prior;
            int closes;

            public ArrayEncoder<Integer> openEncoder(OutputStream target, Runnable checkpoint) {
                if (prior != null) assertSame(prior, checkpoint);
                prior = checkpoint;
                return new ArrayEncoder<>() {
                    public void writeArray(Iterable<? extends Integer> values) throws IOException {
                        checkpoint.run();
                        assertEquals(List.of(1), values);
                        target.write(1);
                    }

                    public void close() {
                        closes++;
                    }
                };
            }
        }
        var codec = new Recording();
        var target =
                new ByteArrayOutputStream() {
                    public void flush() {
                        fail("Caller owns flush");
                    }

                    public void close() {
                        fail("Caller owns close");
                    }
                };
        Thread.currentThread().interrupt();
        try {
            codec.encodeArray(List.of(1), target);
            codec.encodeArray(List.of(1), target);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, codec.closes);
            assertArrayEquals(new byte[] {1, 1}, target.toByteArray());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void writeFailureClosesExactlyOnceAndRetainsTheSuppressedCloseFailure() {
        var write = new IOException("write failed");
        var close = new IOException("close failed");
        int[] closes = {0};
        var codec =
                new Codec() {
                    public ArrayEncoder<Integer> openEncoder(
                            OutputStream target, Runnable checkpoint) {
                        return new ArrayEncoder<>() {
                            public void writeArray(Iterable<? extends Integer> values)
                                    throws IOException {
                                throw write;
                            }

                            public void close() throws IOException {
                                closes[0]++;
                                throw close;
                            }
                        };
                    }
                };
        var failure =
                assertThrows(
                        IOException.class,
                        () -> codec.encodeArray(List.of(1), new ByteArrayOutputStream()));
        assertSame(write, failure);
        assertArrayEquals(new Throwable[] {close}, failure.getSuppressed());
        assertEquals(1, closes[0]);
    }

    @Test
    void openingFailureIsPropagatedWithoutWritingOrClosingTheTarget() {
        var failure = new IOException("open failed");
        var codec =
                new Codec() {
                    public ArrayEncoder<Integer> openEncoder(
                            OutputStream target, Runnable checkpoint) throws IOException {
                        throw failure;
                    }
                };
        var target =
                new OutputStream() {
                    public void write(int value) {
                        fail("Must not write");
                    }

                    public void flush() {
                        fail("Caller owns flush");
                    }

                    public void close() {
                        fail("Caller owns close");
                    }
                };
        assertSame(
                failure,
                assertThrows(IOException.class, () -> codec.encodeArray(List.of(1), target)));
    }
}

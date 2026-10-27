package com.reactor.cachedb.json;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;

class JsonUtf8WriterTest {
    @Test
    void everyValidSupplementaryCodePointMatchesJdkUtf8() throws Exception {
        for (int first = Character.MIN_SUPPLEMENTARY_CODE_POINT;
                first <= Character.MAX_CODE_POINT;
                first += 16384) {
            StringBuilder value = new StringBuilder();
            for (int point = first; point < first + 16384; point++)
                value.appendCodePoint(point).append('a');
            verify(value.toString(), 4093);
        }
    }

    @Test
    void everyUtf16CodeUnitAndSeededStringsMatchTheExistingWriter() throws Exception {
        for (int block = 0; block <= Character.MAX_VALUE; block += 1024) {
            StringBuilder value = new StringBuilder();
            for (int c = block; c < block + 1024; c++)
                value.append('a').append((char) c).append('b');
            verify(value.toString(), 257);
        }
        Random random = new Random(140);
        for (int run = 0; run < 100; run++) {
            char[] value = new char[300 + random.nextInt(1000)];
            for (int i = 0; i < value.length; i++) value[i] = (char) random.nextInt(65536);
            verify(new String(value), 1 + random.nextInt(100));
        }
    }

    @Test
    void bufferEdgesUnicodeTransitionAndSplitSurrogatesRetainBytesAtEveryFlush() throws Exception {
        for (int length : new int[] {0, 1, 8191, 8192, 8193, 16383, 16384, 16385}) {
            verify("a".repeat(length), 257);
            verify("a".repeat(length) + "\u0130\u011f\u00fc\ud83d\ude80" + "b".repeat(16385), 113);
        }
        for (String value :
                new String[] {
                    "\ud800", "\udc00", "\ud800\udc00", "\ud800x", "\ud800\ud800", "\udc00\ud800"
                }) {
            verify(value, 1);
        }
    }

    @Test
    void failuresPropagateAndTheBorrowedStreamIsNeverFlushedOrClosed() throws Exception {
        var failure = new IOException("output failure");
        class Target extends ByteArrayOutputStream {
            boolean broken;

            @Override
            public void write(byte[] bytes, int offset, int length) {
                if (broken) throw new UncheckedIOException(failure);
                super.write(bytes, offset, length);
            }

            @Override
            public void flush() {
                fail("Caller owns flush");
            }

            @Override
            public void close() {
                fail("Caller owns close");
            }
        }
        for (String value : new String[] {"ascii", "\u0130\ud83d\ude80"}) {
            var target = new Target();
            var writer = new JsonUtf8Writer(target);
            writer.write(value);
            writer.flush();
            writer.close();
            assertEquals(value, target.toString(StandardCharsets.UTF_8));
            target.broken = true;
            writer.write("x");
            assertSame(failure, assertThrows(UncheckedIOException.class, writer::flush).getCause());
        }
        var writer = new JsonUtf8Writer(new ByteArrayOutputStream());
        assertThrows(IndexOutOfBoundsException.class, () -> writer.write("a", -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> writer.write(new char[1], 0, 2));
        for (String value : new String[] {"ascii", "\u0130\ud83d\ude80"}) {
            OutputStream broken =
                    new OutputStream() {
                        public void write(int ignored) throws IOException {
                            throw failure;
                        }

                        public void write(byte[] bytes, int offset, int length) throws IOException {
                            throw failure;
                        }
                    };
            var failed = new JsonUtf8Writer(broken);
            failed.write(value);
            assertSame(failure, assertThrows(IOException.class, failed::flush));
        }
    }

    private static void verify(String value, int chunk) throws Exception {
        var actualBytes = new ByteArrayOutputStream();
        var expectedBytes = new ByteArrayOutputStream();
        var actual = new JsonUtf8Writer(actualBytes);
        var expected =
                new BufferedWriter(new OutputStreamWriter(expectedBytes, StandardCharsets.UTF_8));
        char[] chars = value.toCharArray();
        int operation = 0;
        for (int offset = 0; offset < value.length(); ) {
            int length = Math.min(chunk, value.length() - offset);
            switch (operation++ % 3) {
                case 0 -> {
                    actual.write(value, offset, length);
                    expected.write(value, offset, length);
                }
                case 1 -> {
                    actual.write(chars, offset, length);
                    expected.write(chars, offset, length);
                }
                default -> {
                    length = 1;
                    actual.write(chars[offset]);
                    expected.write(chars[offset]);
                }
            }
            offset += length;
            actual.flush();
            expected.flush();
            assertArrayEquals(expectedBytes.toByteArray(), actualBytes.toByteArray());
        }
        actual.close();
        // Existing array encoding flushes, but deliberately never closes the underlying JDK writer.
        expected.flush();
        assertArrayEquals(expectedBytes.toByteArray(), actualBytes.toByteArray());
    }
}

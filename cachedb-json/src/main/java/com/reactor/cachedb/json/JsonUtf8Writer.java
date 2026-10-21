package com.reactor.cachedb.json;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.Objects;

/** One bounded UTF-8 buffer, retaining JDK replacement and split-surrogate/flush semantics. */
final class JsonUtf8Writer extends Writer {
    private final OutputStream target;
    private final byte[] bytes = new byte[8192];
    private int count;
    private char highSurrogate;

    JsonUtf8Writer(OutputStream target) {
        this.target = Objects.requireNonNull(target);
    }

    @Override
    public void write(int value) throws IOException {
        emit((char) value);
    }

    @Override
    public void write(String value, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, value.length());
        int end = offset + length;
        for (int i = offset; i < end; i++) emit(value.charAt(i));
    }

    @Override
    public void write(char[] value, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, value.length);
        int end = offset + length;
        for (int i = offset; i < end; i++) emit(value[i]);
    }

    private void emit(char c) throws IOException {
        // At most four bytes: a pair, or one replacement byte plus a three-byte BMP value.
        if (count > bytes.length - 4) flush();
        if (highSurrogate == 0 && c < 0x800) {
            if (c >= 0x80) bytes[count++] = (byte) (0xc0 | c >>> 6);
            bytes[count++] = (byte) (c < 0x80 ? c : 0x80 | (c & 0x3f));
        } else {
            emitMultibyte(c);
        }
    }

    private void emitMultibyte(char c) {
        if (highSurrogate != 0) {
            if (Character.isLowSurrogate(c)) {
                int point = Character.toCodePoint(highSurrogate, c);
                bytes[count++] = (byte) (0xf0 | point >>> 18);
                bytes[count++] = (byte) (0x80 | (point >>> 12 & 0x3f));
                bytes[count++] = (byte) (0x80 | (point >>> 6 & 0x3f));
                bytes[count++] = (byte) (0x80 | (point & 0x3f));
                highSurrogate = 0;
                return;
            }
            bytes[count++] = '?';
            highSurrogate = 0;
        }
        if (c < 0x80) {
            bytes[count++] = (byte) c;
        } else if (c < 0x800) {
            bytes[count++] = (byte) (0xc0 | c >>> 6);
            bytes[count++] = (byte) (0x80 | (c & 0x3f));
        } else if (Character.isHighSurrogate(c)) {
            highSurrogate = c;
        } else if (Character.isLowSurrogate(c)) {
            bytes[count++] = '?';
        } else {
            bytes[count++] = (byte) (0xe0 | c >>> 12);
            bytes[count++] = (byte) (0x80 | (c >>> 6 & 0x3f));
            bytes[count++] = (byte) (0x80 | (c & 0x3f));
        }
    }

    @Override
    public void flush() throws IOException {
        if (count != 0) {
            target.write(bytes, 0, count);
            count = 0;
        }
    }

    @Override
    public void close() throws IOException {
        // Like the previous encoder's flush-only lifecycle, retain a trailing high surrogate.
        // Never flush/close the borrowed target; the array encoder owns failure/closed state.
        flush();
    }
}

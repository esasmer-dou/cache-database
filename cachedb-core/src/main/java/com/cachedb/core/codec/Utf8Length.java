package com.reactor.cachedb.core.codec;

/**
 * Exact UTF-8 size without allocating the encoded payload; malformed UTF-16 uses JDK replacement.
 */
public final class Utf8Length {
    private Utf8Length() {}

    public static long of(String value) {
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 128) bytes++;
            else if (c < 2048) bytes += 2;
            else if (Character.isHighSurrogate(c)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else bytes += Character.isSurrogate(c) ? 1 : 3;
        }
        return bytes;
    }
}

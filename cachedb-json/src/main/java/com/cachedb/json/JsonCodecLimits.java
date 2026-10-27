package com.reactor.cachedb.json;

/** Bounds apply only to explicitly selected generated codecs, not legacy Jackson configuration. */
public record JsonCodecLimits(
        int maxDepth, int maxArrayElements, int maxNumberDigits, int maxDocumentChars) {
    public JsonCodecLimits {
        if (maxDepth < 1
                || maxDepth > 256
                || maxArrayElements < 1
                || maxNumberDigits < 1
                || maxNumberDigits > 10000
                || maxDocumentChars < 2)
            throw new IllegalArgumentException("Invalid JSON codec limits");
    }

    public static JsonCodecLimits defaults() {
        return new JsonCodecLimits(64, 100_000, 1024, 16 * 1024 * 1024);
    }
}

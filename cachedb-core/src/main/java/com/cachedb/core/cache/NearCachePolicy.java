package com.reactor.cachedb.core.cache;

public record NearCachePolicy(int maxEntries, int ttlSeconds, long maxBytes, int softTtlSeconds) {
    public NearCachePolicy(int maxEntries, int ttlSeconds, long maxBytes) {
        this(maxEntries, ttlSeconds, maxBytes, 0);
    }
    @Override public final boolean equals(Object other) {
        return this == other || other instanceof NearCachePolicy value && maxEntries == value.maxEntries
                && ttlSeconds == value.ttlSeconds && maxBytes == value.maxBytes && softTtlSeconds == value.softTtlSeconds;
    }
    @Override public final int hashCode() {
        int hash = 31 * (31 * maxEntries + ttlSeconds) + Long.hashCode(maxBytes);
        return softTtlSeconds == 0 ? hash : 31 * hash + softTtlSeconds;
    }
    public NearCachePolicy {
        if (maxEntries < 0 || maxEntries > 64_000 || ttlSeconds < 0 || ttlSeconds > 60
                || maxBytes < 0 || maxBytes > 64L * 1024 * 1024)
            throw new IllegalArgumentException("Invalid bounded near-cache policy");
        if (maxEntries > 0 && (ttlSeconds == 0 || maxBytes < 1024))
            throw new IllegalArgumentException("Enabled near cache requires TTL and byte limits");
        if (softTtlSeconds < 0 || softTtlSeconds > 0 && (ttlSeconds == 0 || softTtlSeconds >= ttlSeconds))
            throw new IllegalArgumentException("Soft TTL must be zero or smaller than hard TTL");
    }
    public static NearCachePolicy disabled() { return new NearCachePolicy(0, 0, 0); }
    public boolean enabled() { return maxEntries > 0; }
}

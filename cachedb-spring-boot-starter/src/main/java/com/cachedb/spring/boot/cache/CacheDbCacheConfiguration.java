package com.reactor.cachedb.spring.boot.cache;

import com.reactor.cachedb.core.cache.NearCachePolicy;
import java.time.Duration;
import java.util.Objects;

/** Immutable, validated limits; there are no dynamically created caches. */
public record CacheDbCacheConfiguration(Duration ttl, NearCachePolicy nearCache,
        boolean tenantScoped, boolean allowNullValues, int maxValueBytes) {
    public static final int DEFAULT_VALUE_BYTES = 1024 * 1024;
    public static final int MAX_VALUE_BYTES = 16 * 1024 * 1024;

    public CacheDbCacheConfiguration {
        Objects.requireNonNull(ttl, "Spring Cache TTL is mandatory");
        Objects.requireNonNull(nearCache, "nearCache");
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(7)) > 0
                || ttl.toMillis() == 0 || !ttl.equals(Duration.ofMillis(ttl.toMillis())))
            throw new IllegalArgumentException("Spring Cache TTL must be whole milliseconds in [1ms, 7d]");
        if (maxValueBytes < 1 || maxValueBytes > MAX_VALUE_BYTES)
            throw new IllegalArgumentException("Spring Cache value limit must be in [1, 16777216] bytes");
        if (nearCache.enabled() && nearCache.ttlSeconds() * 1000L > ttl.toMillis())
            throw new IllegalArgumentException("Near-cache TTL must not exceed Redis TTL");
    }
}

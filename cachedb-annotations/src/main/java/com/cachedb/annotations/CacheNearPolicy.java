package com.reactor.cachedb.annotations;

import java.lang.annotation.*;

/** Optional bounded, eventually invalidated point-read cache. Not a consistency boundary. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface CacheNearPolicy {
    int maxEntries() default 1024;
    int ttlSeconds() default 10;
    long maxBytes() default 4 * 1024 * 1024;
    int softTtlSeconds() default 0;
}

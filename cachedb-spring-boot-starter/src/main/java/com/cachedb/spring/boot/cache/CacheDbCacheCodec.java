package com.reactor.cachedb.spring.boot.cache;

import com.reactor.cachedb.core.codec.CacheValueCodec;
import java.util.Objects;

/** One explicit value schema per cache; generated StreamingJsonCodec implementations fit directly. */
public record CacheDbCacheCodec<V>(String cacheName, CacheValueCodec<V> codec) {
    public CacheDbCacheCodec {
        CacheDbCacheKeys.checkName(cacheName);
        Objects.requireNonNull(codec, "codec");
    }
}

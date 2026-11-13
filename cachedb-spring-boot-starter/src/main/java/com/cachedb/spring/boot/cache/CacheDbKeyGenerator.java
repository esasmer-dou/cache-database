package com.reactor.cachedb.spring.boot.cache;

import java.lang.reflect.Method;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKey;

/** Native scalar/empty keys, with an injective reserved domain for typed composite Strings. */
public final class CacheDbKeyGenerator implements KeyGenerator {
    private static final String RESERVED = "ck1:";
    @Override public Object generate(Object target, Method method, Object... params) {
        if (params.length > 128) throw new IllegalArgumentException("Composite cache keys support at most 128 parameters");
        if (params.length == 0) return SimpleKey.EMPTY;
        if (params.length == 1 && params[0] != null) {
            Object scalar = params[0];
            String part = CacheDbCacheKeys.scalar(scalar); // Validate bounded native identity without reflection.
            if (!(scalar instanceof String text) || !text.startsWith(RESERVED)) return scalar;
            return bounded(RESERVED + "single:" + part.length() + ":" + part);
        }
        StringBuilder key = new StringBuilder(RESERVED + "args:" + params.length + ":");
        for (Object param : params) {
            String part = param == null ? "null:" : CacheDbCacheKeys.scalar(param);
            if ((long) key.length() + part.length() + 12 > CacheDbCacheKeys.MAX_INPUT_CHARS)
                throw new IllegalArgumentException("Composite cache key exceeds 65536 characters");
            key.append(part.length()).append(':').append(part);
        }
        // CacheDbCacheKeys.encode applies UTF-8/base64url and h:SHA-256 past 1024 encoded characters.
        return key.toString();
    }
    private static String bounded(String value) {
        if (value.length() > CacheDbCacheKeys.MAX_INPUT_CHARS)
            throw new IllegalArgumentException("Generated cache key exceeds 65536 characters");
        return value;
    }
}

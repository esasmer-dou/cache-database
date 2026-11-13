package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.protection.ProtectionException;
import com.reactor.cachedb.redis.JedisPooledOperations;

/** First INITIAL profile only; pure checks precede SQL birth, JDBC phases and native dispatch. */
final class CacheInitialProfile {
    private CacheInitialProfile() {}

    static void requireSupported(CacheDatabase database) {
        if (!(database.redisOperations() instanceof JedisPooledOperations foreground)
                || !(database.backgroundRedisOperations() instanceof JedisPooledOperations background)
                || foreground.pooledClient() != background.pooledClient()) reject();
        CachePolicy defaults = database.config().resourceLimits().defaultCachePolicy();
        if (defaults == null || defaults.nearCachePolicy().enabled()) reject();
        int examined = 0;
        for (var binding : database.entityRegistry().all()) {
            if (++examined > 10_000) reject();
            CachePolicy configured = binding.cachePolicy() == null ? defaults : binding.cachePolicy();
            var annotation = binding.metadata().nearCachePolicy();
            // Repository construction falls back to annotation policy when configured L1 is disabled.
            if (annotation == null || configured.nearCachePolicy().enabled() || annotation.enabled()) reject();
        }
    }

    static void requireStandaloneServer(CacheDatabase database) {
        requireSupported(database);
        Object raw;
        try { raw = database.redisOperations().serverInfo("cluster", ""); }
        catch (RuntimeException unavailable) {
            throw new ProtectionException(ProtectionException.Code.STORAGE_UNAVAILABLE);
        }
        String info = raw instanceof String text ? text
                : raw instanceof byte[] bytes && bytes.length <= 16_384
                ? new String(bytes, java.nio.charset.StandardCharsets.US_ASCII) : null;
        if (info == null || info.length() > 16_384
                || !(info.startsWith("cluster_enabled:0\r\n") || info.contains("\r\ncluster_enabled:0\r\n"))) reject();
    }

    private static void reject() {
        throw new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
    }
}

package com.reactor.cachedb.spring.boot.cache;

import com.reactor.cachedb.core.cache.NearCachePolicy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cachedb.spring-cache")
public record CacheDbSpringCacheProperties(String keyPrefix, Map<String, CacheSpec> caches,
        Integer maxConcurrentOperations, Integer maxInFlightKeys, Integer maxWaiters, Duration loadTimeout) {
    public CacheDbSpringCacheProperties {
        caches = caches == null ? Map.of() : caches;
        maxConcurrentOperations = maxConcurrentOperations == null ? 256 : maxConcurrentOperations;
        maxInFlightKeys = maxInFlightKeys == null ? 1024 : maxInFlightKeys;
        maxWaiters = maxWaiters == null ? 4096 : maxWaiters;
        loadTimeout = loadTimeout == null ? Duration.ofSeconds(30) : loadTimeout;
        if (caches.isEmpty() || caches.size() > 256)
            throw new IllegalArgumentException("Configure between 1 and 256 Spring caches");
        caches = Map.copyOf(caches);
        if (maxConcurrentOperations < 1 || maxConcurrentOperations > 4096
                || maxInFlightKeys < 1 || maxInFlightKeys > 4096 || maxWaiters < 1 || maxWaiters > 65536
                || loadTimeout.isNegative() || loadTimeout.compareTo(Duration.ofMinutes(5)) > 0
                || loadTimeout.toMillis() < 1)
            throw new IllegalArgumentException("Invalid bounded Spring Cache concurrency settings");
    }

    public Map<String, CacheDbCacheConfiguration> configurations() {
        Map<String, CacheDbCacheConfiguration> result = new LinkedHashMap<>();
        caches.forEach((name, spec) -> result.put(name, spec.configuration()));
        return Map.copyOf(result);
    }

    public record CacheSpec(Duration ttl, NearSpec nearCache, Boolean tenantScoped,
            Boolean allowNullValues, Integer maxValueBytes) {
        CacheDbCacheConfiguration configuration() {
            return new CacheDbCacheConfiguration(ttl,
                    nearCache == null ? NearCachePolicy.disabled() : nearCache.policy(),
                    Boolean.TRUE.equals(tenantScoped), Boolean.TRUE.equals(allowNullValues),
                    maxValueBytes == null ? CacheDbCacheConfiguration.DEFAULT_VALUE_BYTES : maxValueBytes);
        }
    }

    public record NearSpec(Integer maxEntries, Long maxBytes, Duration ttl, Duration softTtl) {
        NearCachePolicy policy() {
            return new NearCachePolicy(maxEntries == null ? 0 : maxEntries, seconds(ttl),
                    maxBytes == null ? 0 : maxBytes, seconds(softTtl));
        }
        private static int seconds(Duration duration) {
            if (duration == null) return 0;
            if (duration.isNegative() || duration.compareTo(Duration.ofSeconds(60)) > 0
                    || duration.getNano() != 0)
                throw new IllegalArgumentException("Near-cache TTLs must be whole seconds in [0, 60]");
            return Math.toIntExact(duration.getSeconds());
        }
    }
}

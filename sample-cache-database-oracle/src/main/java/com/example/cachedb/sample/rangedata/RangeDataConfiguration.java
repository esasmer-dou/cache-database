package com.example.cachedb.sample.rangedata;

import com.reactor.cachedb.core.config.ReadThroughMode;
import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
@ConditionalOnProperty(name = "sample.range-data.enabled", havingValue = "true")
public class RangeDataConfiguration {
    @Bean RangeRoutes rangeRoutes(CacheDatabase database) {
        requireRedisOnly(database);
        var repository = database.repository(RangeDataRowCacheBinding.METADATA, RangeDataRowCacheBinding.CODEC);
        if (!(repository instanceof com.reactor.cachedb.redis.RedisEntityRepository<?, ?> nativeRepository))
            throw new IllegalStateException("Range demo requires the native Redis repository");
        // Startup maintenance only: request paths remain strict and never scan degraded indexes.
        try (var scope = com.reactor.cachedb.core.tenant.CacheTenantContext.administrative()) {
            var result = nativeRepository.rebuildCompositeIndexesFenced(false, "demo range index startup");
            if (!result.degradedCleared()) throw new IllegalStateException("Range index startup verification failed");
        }
        return new RangeRoutesCacheDbImplementation(database);
    }
    @Bean RangeDataRepository rangeDataRepository(CacheDatabase database) {
        requireRedisOnly(database);
        return new RangeDataRepositoryCacheDbImplementation(database);
    }
    private static void requireRedisOnly(CacheDatabase database) {
        if (database.config().readThrough().mode() != ReadThroughMode.REDIS_ONLY)
            throw new IllegalStateException("Range/data demo requires REDIS_ONLY; it does not offer SQL fallback");
    }
}

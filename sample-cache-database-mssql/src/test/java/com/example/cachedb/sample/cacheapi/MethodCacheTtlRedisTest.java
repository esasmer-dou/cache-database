package com.example.cachedb.sample.cacheapi;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.redis.RedisClientOperations;
import com.reactor.cachedb.spring.boot.cache.CacheDbSpringCacheAutoConfiguration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

@EnabledIfEnvironmentVariable(named = "CACHEDB_TEST_NAMESPACE", matches = "cachedb-[a-z0-9-]+")
@Timeout(15)
class MethodCacheTtlRedisTest {
    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class Interception {}

    @Test void actualAnnotationReloadsAfterRedisTtl() {
        String namespace = System.getenv("CACHEDB_TEST_NAMESPACE");
        String uri = System.getenv("CACHEDB_TEST_REDIS_URI");
        assertNotNull(uri);
        assertTrue(uri.contains("." + namespace + ".svc.cluster.local"), "Dedicated external Redis required");
        try (var jedis = new JedisPooled(uri)) {
            RedisClientOperations redis = new RedisClientOperations() {
                @Override public redis.clients.jedis.UnifiedJedis commands() { return jedis; }
                @Override public boolean clustered() { return false; }
                @Override public void close() { /* The enclosing fixture owns the borrowed client. */ }
            };
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(CacheDbSpringCacheAutoConfiguration.class))
                    .withUserConfiguration(Interception.class)
                    .withBean(RedisClientOperations.class, () -> redis)
                    .withBean(MethodCacheService.class, MethodCacheService::new)
                    .withPropertyValues("cachedb.spring-cache.enabled=true",
                            "cachedb.spring-cache.key-prefix=method-ttl-" + UUID.randomUUID(),
                            "cachedb.spring-cache.caches.quotes.ttl=300ms",
                            "cachedb.spring-cache.caches.quotes.tenant-scoped=true",
                            "cachedb.spring-cache.caches.nulls.ttl=300ms",
                            "cachedb.spring-cache.caches.nulls.tenant-scoped=true",
                            "cachedb.spring-cache.caches.nulls.allow-null-values=true")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var service = context.getBean(MethodCacheService.class);
                        try (var tenant = CacheTenantContext.withTenant("shop-a")) {
                            String first = service.quote("TTL", 1);
                            assertEquals(first, service.quote("TTL", 1));
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                            while (System.nanoTime() < deadline) {
                                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                                if (!first.equals(service.quote("TTL", 1))) {
                                    assertEquals(2, service.sourceLoads()); return;
                                }
                            }
                            fail("Redis TTL did not expire within the bounded deadline");
                        }
                    });
        }
    }
}

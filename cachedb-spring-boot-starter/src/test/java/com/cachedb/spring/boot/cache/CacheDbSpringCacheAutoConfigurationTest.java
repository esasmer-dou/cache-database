package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.reactor.cachedb.redis.RedisClientOperations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

class CacheDbSpringCacheAutoConfigurationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withPropertyValues("cachedb.enabled=false")
                .withConfiguration(AutoConfigurations.of(
                        com.reactor.cachedb.spring.boot.CacheDatabaseSpringBootAutoConfiguration.class,
                        CacheDbSpringCacheAutoConfiguration.class, CacheAutoConfiguration.class));
    }
    private ApplicationContextRunner enabled() {
        return runner().withPropertyValues("cachedb.spring-cache.enabled=true", "cachedb.spring-cache.caches.test.ttl=1m")
                .withBean(RedisClientOperations.class, () -> mock(RedisClientOperations.class));
    }

    @Test void optInOnlyAndSpringCacheClassConditionAreEffective() {
        runner().run(context -> assertFalse(context.containsBean("cacheDbCacheManager")));
        runner().withPropertyValues("cachedb.spring-cache.enabled=false")
                .run(context -> assertFalse(context.containsBean("cacheDbCacheManager")));
        runner().withClassLoader(new FilteredClassLoader("org.springframework.cache"))
                .withPropertyValues("cachedb.spring-cache.enabled=true")
                .run(context -> assertFalse(context.containsBean("cacheDbCacheManager")));
    }

    @Test void userCacheManagerBacksOffEvenWithInvalidOrMissingCachedbSettings() {
        runner().withPropertyValues("cachedb.spring-cache.enabled=true")
                .withBean(CacheManager.class, ConcurrentMapCacheManager::new)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("cacheDbCacheManager"));
                    assertFalse(context.containsBean("cacheDbCachingConfigurer"));
                });
    }

    @Test void propertyBindingCreatesExactlyOneFixedManagerAndOptionalMetrics() {
        enabled().withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(CacheManager.class).size());
            var manager = context.getBean(CacheDbCacheManager.class);
            assertNotNull(manager.getCache("test"));
            assertNull(manager.getCache("undeclared"));
            assertTrue(context.getBean("cacheDbKeyGenerator") instanceof CacheDbKeyGenerator);
            assertEquals(1, context.getBeansOfType(CachingConfigurer.class).size());
            var metrics = context.getBean(CacheDbCacheMetrics.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            metrics.bindTo(registry);
            assertNotNull(registry.find("cachedb.spring.cache.hits").tag("cache", "test").functionCounter());
            verifyNoInteractions(context.getBean(RedisClientOperations.class));
        });
    }

    @Test void explicitCachingConfigurerIsNeverOverridden() {
        enabled().withBean(CachingConfigurer.class, () -> new CachingConfigurer() {})
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("cacheDbCachingConfigurer"));
                    assertEquals(1, context.getBeansOfType(CachingConfigurer.class).size());
                });
    }

    @Test void missingTtlInvalidNearPolicyMissingRedisAndOversizedTtlFailStartup() {
        runner().withPropertyValues("cachedb.spring-cache.enabled=true", "cachedb.spring-cache.caches.test.tenant-scoped=true")
                .withBean(RedisClientOperations.class, () -> mock(RedisClientOperations.class))
                .run(context -> assertNotNull(context.getStartupFailure()));
        enabled().withPropertyValues("cachedb.spring-cache.caches.test.ttl=8d")
                .run(context -> assertNotNull(context.getStartupFailure()));
        enabled().withPropertyValues("cachedb.spring-cache.caches.test.near-cache.max-entries=100")
                .run(context -> assertNotNull(context.getStartupFailure()));
        runner().withPropertyValues("cachedb.spring-cache.enabled=true", "cachedb.spring-cache.caches.test.ttl=1m")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.cache.annotation.EnableCaching
    static class ExistingConfigurerApplication {
        @org.springframework.context.annotation.Bean
        CachingConfigurer applicationCachingConfigurer() { return new CachingConfigurer() {}; }
        @org.springframework.context.annotation.Bean
        MultiArgumentService multiArgumentService() { return new MultiArgumentService(); }
    }
    static class MultiArgumentService {
        @org.springframework.cache.annotation.Cacheable("test")
        public String read(String first, int second) { throw new AssertionError("Opaque key must fail before the source"); }
    }
    @Test void existingConfigurerGetsActionableOpaqueSimpleKeyFailureWithoutRedisAccess() {
        enabled().withUserConfiguration(ExistingConfigurerApplication.class).run(context -> {
            assertNull(context.getStartupFailure());
            var error = assertThrows(IllegalArgumentException.class,
                    () -> context.getBean(MultiArgumentService.class).read("one", 2));
            assertTrue(error.getMessage().contains("CachingConfigurer"));
            assertTrue(error.getMessage().contains("cacheDbKeyGenerator"));
            verifyNoInteractions(context.getBean(RedisClientOperations.class));
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.cache.annotation.EnableCaching
    static class FutureApplication {
        @org.springframework.context.annotation.Bean
        FutureService futureService() { return new FutureService(); }
    }
    static class FutureService {
        @org.springframework.cache.annotation.Cacheable("test")
        public java.util.concurrent.CompletableFuture<String> read(String id) {
            throw new AssertionError("Unsupported retrieve must fail before the source");
        }
        @org.springframework.cache.annotation.Cacheable(cacheNames = "test", sync = true)
        public java.util.concurrent.CompletableFuture<String> synchronizedRead(String id) {
            throw new AssertionError("Unsupported retrieve must fail before the source");
        }
    }
    @Test void futureAnnotationsUseUnsupportedRetrieveRatherThanSecretlyBlockingOrInvokingTheSource() {
        enabled().withUserConfiguration(FutureApplication.class).run(context -> {
            assertNull(context.getStartupFailure());
            var service = context.getBean(FutureService.class);
            assertThrows(UnsupportedOperationException.class, () -> service.read("key"));
            assertThrows(UnsupportedOperationException.class, () -> service.synchronizedRead("key"));
            verifyNoInteractions(context.getBean(RedisClientOperations.class));
        });
    }
}

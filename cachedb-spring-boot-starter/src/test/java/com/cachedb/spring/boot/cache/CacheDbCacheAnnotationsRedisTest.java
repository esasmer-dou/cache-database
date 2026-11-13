package com.reactor.cachedb.spring.boot.cache;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import org.springframework.aop.support.AopUtils;
import com.reactor.cachedb.redis.RedisClientOperations;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
@Timeout(45)
class CacheDbCacheAnnotationsRedisTest {
    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class Services {
        @Bean MethodService methodService() { return new MethodService(); }
    }
    static class MethodService {
        final AtomicInteger calls = new AtomicInteger(), nullCalls = new AtomicInteger(), tenantCalls = new AtomicInteger();
        final AtomicInteger tenantClearCalls = new AtomicInteger();
        public int calls() { return calls.get(); }
        public int tenantClearCalls() { return tenantClearCalls.get(); }
        public int nullCalls() { return nullCalls.get(); }
        public int tenantCalls() { return tenantCalls.get(); }
        @Cacheable(cacheNames = "methods", sync = true)
        public String read(String id, int partition) { return "loaded-" + calls.incrementAndGet(); }
        @CachePut(cacheNames = "methods")
        public String update(String id, int partition) { return "written"; }
        @CacheEvict(cacheNames = "methods", allEntries = true)
        public void clearAll() {}
        @Cacheable(cacheNames = "nulls", sync = true)
        public String nullable(String id) { nullCalls.incrementAndGet(); return null; }
        @CacheEvict(cacheNames = "tenants", allEntries = true, beforeInvocation = true)
        public void clearTenantBeforeInvocation() { tenantClearCalls.incrementAndGet(); }
        @Cacheable(cacheNames = "tenants", sync = true)
        public String tenant(String id) {
            tenantCalls.incrementAndGet();
            return CacheTenantContext.currentTenant().orElseThrow();
        }
    }

    @Test void annotationsUseTypedCompositeKeysCachePutGenerationEvictionNullsTtlAndTenantIsolation() {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(CacheDbSpringCacheAutoConfiguration.class, CacheAutoConfiguration.class))
                    .withUserConfiguration(Services.class)
                    .withBean(RedisClientOperations.class, () -> fixture.tracked)
                    .withPropertyValues("cachedb.spring-cache.enabled=true",
                            "cachedb.spring-cache.key-prefix=" + fixture.prefix,
                            "cachedb.spring-cache.caches.methods.ttl=300ms",
                            "cachedb.spring-cache.caches.nulls.ttl=10s",
                            "cachedb.spring-cache.caches.nulls.allow-null-values=true",
                            "cachedb.spring-cache.caches.tenants.ttl=10s",
                            "cachedb.spring-cache.caches.tenants.tenant-scoped=true")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        MethodService service = context.getBean(MethodService.class);
                        assertEquals("loaded-1", service.read("key", 1));
                        assertEquals("loaded-1", service.read("key", 1));
                        assertEquals("written", service.update("key", 1));
                        assertEquals("written", service.read("key", 1));
                        service.clearAll();
                        assertEquals("loaded-2", service.read("key", 1));
                        assertEquals(2, service.calls());
                        assertNull(service.nullable("n"));
                        assertNull(service.nullable("n"));
                        assertEquals(1, service.nullCalls());
                        try (var ignored = CacheTenantContext.withTenant("one")) { assertEquals("one", service.tenant("key")); }
                        try (var ignored = CacheTenantContext.withTenant("two")) { assertEquals("two", service.tenant("key")); }
                        try (var ignored = CacheTenantContext.withTenant("one")) { assertEquals("one", service.tenant("key")); }
                        assertEquals(2, service.tenantCalls());
                        assertThrows(RuntimeException.class, () -> service.tenant("key"));
                        CacheDbCacheRedisFixture.await(() -> !service.read("key", 1).equals("loaded-2"));
                        assertEquals(3, service.calls());
                    });
        }
    }

    @Test void annotationGuardsRejectMissingTenantAndCausalContextBeforeHotL1RedisAndSource() {
        try (var fixture = CacheDbCacheRedisFixture.standalone()) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(CacheDbSpringCacheAutoConfiguration.class, CacheAutoConfiguration.class))
                    .withUserConfiguration(Services.class)
                    .withBean(RedisClientOperations.class, () -> fixture.tracked)
                    .withPropertyValues("cachedb.spring-cache.enabled=true",
                            "cachedb.spring-cache.key-prefix=" + fixture.prefix,
                            "cachedb.spring-cache.caches.methods.ttl=120s",
                            "cachedb.spring-cache.caches.methods.near-cache.max-entries=32",
                            "cachedb.spring-cache.caches.methods.near-cache.max-bytes=65536",
                            "cachedb.spring-cache.caches.methods.near-cache.ttl=30s",
                            "cachedb.spring-cache.caches.nulls.ttl=120s",
                            "cachedb.spring-cache.caches.nulls.allow-null-values=true",
                            "cachedb.spring-cache.caches.tenants.ttl=120s",
                            "cachedb.spring-cache.caches.tenants.tenant-scoped=true",
                            "cachedb.spring-cache.caches.tenants.near-cache.max-entries=32",
                            "cachedb.spring-cache.caches.tenants.near-cache.max-bytes=65536",
                            "cachedb.spring-cache.caches.tenants.near-cache.ttl=30s")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        MethodService service = context.getBean(MethodService.class);
                        assertTrue(AopUtils.isAopProxy(service));
                        var manager = context.getBean(CacheDbCacheManager.class);
                        CacheDbCacheRedisFixture.await(manager::invalidationConnected);
                        var methods = manager.getCache("methods");
                        var tenants = manager.getCache("tenants");
                        assertEquals("loaded-1", service.read("causal", 7));
                        CacheDbCacheRedisFixture.await(() -> {
                            long before = methods.statistics().nearHits();
                            assertEquals("loaded-1", service.read("causal", 7));
                            return methods.statistics().nearHits() > before;
                        });
                        try (var tenant = CacheTenantContext.withTenant("one")) {
                            assertEquals("one", service.tenant("key"));
                            CacheDbCacheRedisFixture.await(() -> {
                                long before = tenants.statistics().nearHits();
                                assertEquals("one", service.tenant("key"));
                                return tenants.statistics().nearHits() > before;
                            });
                        }
                        long evals = fixture.evalCalls.get(), clients = fixture.commandsCalls.get();
                        int sources = service.calls(), tenantSources = service.tenantCalls();
                        assertThrows(CacheTenantAccessDeniedException.class, () -> service.tenant("key"));
                        assertThrows(CacheTenantAccessDeniedException.class, service::clearTenantBeforeInvocation);
                        try (var admin = CacheTenantContext.administrative()) {
                            assertThrows(CacheTenantAccessDeniedException.class, () -> service.tenant("key"));
                            assertThrows(CacheTenantAccessDeniedException.class, service::clearTenantBeforeInvocation);
                        }
                        try (var tenant = CacheTenantContext.withTenant("one");
                             var causal = CacheCausalContext.withMinVersion("entity", "id", 42)) {
                            assertThrows(IllegalStateException.class, () -> service.read("causal", 7));
                            assertThrows(IllegalStateException.class, () -> service.tenant("key"));
                            assertThrows(IllegalStateException.class, service::clearTenantBeforeInvocation);
                        }
                        assertEquals(evals, fixture.evalCalls.get(), "Guards must run before Redis EVAL");
                        assertEquals(clients, fixture.commandsCalls.get(), "Guards must run before Redis client access");
                        assertEquals(sources, service.calls());
                        assertEquals(tenantSources, service.tenantCalls());
                        assertEquals(0, service.tenantClearCalls());
                        assertTrue(CacheTenantContext.currentTenant().isEmpty());
                        assertEquals("", CacheCausalContext.encodeToken());
                        assertEquals("loaded-1", service.read("causal", 7));
                        try (var tenant = CacheTenantContext.withTenant("two")) {
                            assertEquals("two", service.tenant("key"));
                            service.clearTenantBeforeInvocation();
                        }
                        try (var tenant = CacheTenantContext.withTenant("one")) {
                            assertEquals("one", service.tenant("key"));
                        }
                        assertEquals(2, service.tenantCalls());
                        assertEquals(1, service.tenantClearCalls());
                    });
        }
    }
}

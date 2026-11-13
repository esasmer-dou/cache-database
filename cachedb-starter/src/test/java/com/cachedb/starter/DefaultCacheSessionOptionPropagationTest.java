package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.queue.StoragePerformanceCollector;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.redis.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

class DefaultCacheSessionOptionPropagationTest {
    private static RedisClientOperations unused(AtomicInteger closes) {
        return new RedisClientOperations() {
            public UnifiedJedis commands() { throw new AssertionError("Session wiring/capability inspection must not perform I/O"); }
            public void close() { closes.incrementAndGet(); }
        };
    }
    private static DefaultCacheSession session(RedisClientOperations client, CacheDatabaseConfig base,
            CacheRuntimeOptions options) {
        return new DefaultCacheSession(client, client, base, new DefaultEntityRegistry(base.resourceLimits()),
                null, new RedisKeyStrategy("session-options"), null, null, null, new QueryEvaluator(),
                StoragePerformanceCollector.noop(), null, null, options);
    }
    private static RedisEntityRepository<HotPathEvolutionRedisTest.Row, Long> repository(DefaultCacheSession session) {
        return (RedisEntityRepository<HotPathEvolutionRedisTest.Row, Long>) session.repository(
                HotPathEvolutionRedisTest.METADATA, new HotPathEvolutionRedisTest.Codec(), CachePolicy.defaults());
    }
    @Test void rootStyleInjectionPreservesOptionsAndOneBorrowedRuntimeAcrossViews() {
        var closes = new AtomicInteger();
        var client = unused(closes);
        var base = CacheDatabaseConfig.builder().runtimeRole(CacheRuntimeRole.CLIENT_ONLY).build();
        var options = CacheRuntimeOptions.builder().redisReadCoalescing(RedisReadCoalescingConfig.builder()
                .enabled(false).maxLiveReplyBytes(64L * 1024 * 1024).build()).build();
        try (var reads = new RedisRepositoryReadState(client, options.redisReadCoalescing())) {
            var session = session(client, base, options).withReadRuntime(reads);
            assertSame(options, session.runtimeOptions());
            var repository = repository(session);
            var copy = (RedisEntityRepository<HotPathEvolutionRedisTest.Row, Long>) repository.withFetchPlan(FetchPlan.empty());
            assertSame(reads.runtime(), repository.redisReadRuntime());
            assertSame(reads.runtime(), copy.redisReadRuntime());
            assertEquals(repository.capabilities().asList(), copy.capabilities().asList());
            // A read runtime alone cannot supply the deliberately absent function executor.
            assertFalse(repository.capabilities().supports(com.reactor.cachedb.core.api.RepositoryCapability.BULK_DELETE));
            assertThrows(com.reactor.cachedb.core.api.RepositoryCapabilityUnavailableException.class,
                    () -> repository.bulkDelete(java.util.List.of(1L)));
            reads.close();
            assertThrows(RejectedExecutionException.class, () -> repository.findById(1L));
            assertThrows(RejectedExecutionException.class, () -> copy.findHotById(1L));
        }
        assertEquals(0, closes.get());
    }
    @Test void originalOperationsConstructorRemainsDirectAndDoesNotInventALifecycleOwner() {
        var client = unused(new AtomicInteger());
        var base = CacheDatabaseConfig.defaults();
        var session = new DefaultCacheSession(client, client, base, new DefaultEntityRegistry(base.resourceLimits()),
                null, new RedisKeyStrategy("legacy-session"), null, null, null, new QueryEvaluator(),
                StoragePerformanceCollector.noop(), null, null);
        assertFalse(session.runtimeOptions().redisReadCoalescing().enabled());
        var repository = repository(session);
        assertNull(repository.redisReadRuntime());
        for (var capability : com.reactor.cachedb.core.api.RepositoryCapability.values())
            assertEquals(capability != com.reactor.cachedb.core.api.RepositoryCapability.BULK_DELETE,
                    repository.capabilities().supports(capability), capability.name());
        var failure=assertThrows(com.reactor.cachedb.core.api.RepositoryCapabilityUnavailableException.class,
                () -> repository.bulkDelete(java.util.List.of(1L,2L)));
        assertEquals(com.reactor.cachedb.core.api.RepositoryCapability.BULK_DELETE,failure.capability());
    }
    @Test void originalPooledConstructorRemainsSourceCompatibleAndBorrowsTheClient() {
        var base = CacheDatabaseConfig.defaults();
        // Pool construction is lazy; no Redis commands or borrows occur in this test.
        try (var pooled = new JedisPooled("redis://127.0.0.1:1")) {
            var session = new DefaultCacheSession(pooled, pooled, base,
                    new DefaultEntityRegistry(base.resourceLimits()), null, new RedisKeyStrategy("pooled-session"),
                    null, null, null, new QueryEvaluator(), StoragePerformanceCollector.noop(), null, null);
            assertFalse(session.runtimeOptions().redisReadCoalescing().enabled());
            assertNull(repository(session).redisReadRuntime());
            assertFalse(pooled.getPool().isClosed());
            assertEquals(0, pooled.getPool().getNumActive());
        }
    }
}

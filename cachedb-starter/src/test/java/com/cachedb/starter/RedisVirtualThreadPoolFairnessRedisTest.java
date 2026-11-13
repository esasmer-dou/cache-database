package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "cachedb.snapshot.redis", matches = "true")
class RedisVirtualThreadPoolFairnessRedisTest {
    private static RedisConnectionConfig config() {
        String host = System.getProperty("cachedb.snapshot.redis.host", "127.0.0.1");
        int port = Integer.getInteger("cachedb.snapshot.redis.port", 16383);
        return RedisConnectionConfig.builder().uri("redis://" + host + ":" + port + "/14")
                .poolMaxTotal(32).poolMaxIdle(32).poolMinIdle(0).poolMaxWaitMillis(2000)
                .connectionTimeoutMillis(3000).readTimeoutMillis(5000).build();
    }

    @Test void defaultFairAdmissionAllowsEveryVirtualCallerToProgressUnderSustainedContention() throws Exception {
        var configuration = config();
        assertEquals(configuration.uri(), unchangedRecordPattern(configuration));
        try (var redis = configuration.createClient()) {
            String key = "fairness-" + UUID.randomUUID();
            redis.set(key, "value");
            try {
                exerciseEveryCaller(() -> {
                    var values = redis.mget(key, key + ":missing");
                    assertEquals("value", values.getFirst());
                    assertNull(values.get(1));
                });
                assertEquals(0, redis.getPool().getNumActive());
                assertTrue(redis.getPool().getNumIdle() <= 32);
            } finally { redis.del(key); }
        }
    }

    @Test void repositoryGuardrailSamplingDoesNotPinOrStarveVirtualCallers() throws Exception {
        String prefix = "guard-liveness-" + UUID.randomUUID();
        var source = new org.h2.jdbcx.JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + prefix);
        var base = com.reactor.cachedb.core.config.CacheDatabaseConfig.builder()
                .runtimeRole(com.reactor.cachedb.core.config.CacheRuntimeRole.CLIENT_ONLY)
                .keyspace(com.reactor.cachedb.core.config.KeyspaceConfig.builder().keyPrefix(prefix).build())
                .adminReportJob(com.reactor.cachedb.core.config.AdminReportJobConfig.builder()
                        .diagnosticsStreamKey(prefix + ":diagnostics").build())
                .writeBehind(com.reactor.cachedb.core.config.WriteBehindConfig.builder()
                        .streamKey(prefix + ":writes").compactionStreamKey(prefix + ":compaction")
                        .deadLetterStreamKey(prefix + ":dlq").consumerGroup(prefix)
                        .durableCompactionEnabled(false).build())
                .redisGuardrail(com.reactor.cachedb.core.config.RedisGuardrailConfig.builder()
                        .sampleIntervalMillis(25).writeBehindBacklogWarnThreshold(1)
                        .warnSamplesToBalanced(1).build()).build();
        var policy = com.reactor.cachedb.core.cache.CachePolicy.builder()
                .hotEntityLimit(100).lruEvictionEnabled(false).build();
        try (var redis = config().createClient(); var database = new CacheDatabase(redis, source, base)) {
            var codec = new HotPathEvolutionRedisTest.Codec();
            database.register(HotPathEvolutionRedisTest.METADATA, codec, policy);
            var repository = database.repository(HotPathEvolutionRedisTest.METADATA, codec, policy);
            database.start();
            repository.save(new HotPathEvolutionRedisTest.Row(1L, "guard-row", 5));
            try { exerciseEveryCaller(() -> assertEquals(1L, repository.findById(1L).orElseThrow().id)); }
            catch (AssertionError failure) {
                throw new AssertionError("Repository pool max wait=" + redis.getPool().getMaxBorrowWaitTimeMillis()
                        + "ms; mean wait=" + redis.getPool().getMeanBorrowWaitTimeMillis()
                        + "ms; active=" + redis.getPool().getNumActive(), failure);
            }
            assertFalse(database.runtimeProfileEvents().isEmpty(), "The runtime profile switch must actually execute");
            assertTrue(redis.xlen(prefix + ":diagnostics") > 0, "Profile diagnostics must reach Redis");
            assertEquals(0, redis.getPool().getNumActive());
            assertTrue(redis.getPool().getNumIdle() <= 32);
        }
    }

    private static void exerciseEveryCaller(Runnable operation) throws Exception {
            var ready = new CountDownLatch(1000);
            var start = new CountDownLatch(1);
            var permits = new Semaphore(1000);
            var workers = new ArrayList<Thread>(1000);
            var counts = new AtomicIntegerArray(1000);
            var firstError = new AtomicReference<Throwable>();
            long[] deadline = new long[1];
            try {
                for (int i = 0; i < 1000; i++) {
                    int slot = i;
                    assertTrue(permits.tryAcquire());
                    workers.add(Thread.ofVirtual().name("cachedb-fair-pool-" + i)
                            .inheritInheritableThreadLocals(false).start(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            while (System.nanoTime() < deadline[0] && counts.get(slot) < 100000) {
                                operation.run();
                                counts.incrementAndGet(slot);
                            }
                        } catch (Throwable failure) { firstError.compareAndSet(null, failure); }
                        finally { permits.release(); }
                    }));
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                deadline[0] = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                start.countDown();
                long finish = deadline[0] + TimeUnit.SECONDS.toNanos(5);
                for (Thread worker : workers) {
                    long remaining = finish - System.nanoTime();
                    assertTrue(remaining > 0 && worker.join(Duration.ofNanos(remaining)), "Worker exceeded total deadline");
                }
                if (firstError.get() != null) throw new AssertionError("Virtual caller failed", firstError.get());
                for (int i = 0; i < 1000; i++) assertTrue(counts.get(i) > 0, "A virtual caller was starved");
                assertEquals(1000, permits.availablePermits());
            } finally {
                start.countDown();
                for (Thread worker : workers) if (worker.isAlive()) worker.interrupt();
            }
    }

    @Test void explicitLegacyAdmissionRemainsAnAdditiveOverload() {
        try (var redis = config().createClient(false)) { assertEquals("PONG", redis.ping()); }
    }

    private static String unchangedRecordPattern(Object value) {
        return switch (value) {
            case RedisConnectionConfig(var uri, var max, var idle, var min, var wait, var block,
                    var borrow, var idleTest, var eviction, var minIdle, var tests, var connect,
                    var read, var blockingRead) -> uri;
            default -> throw new AssertionError("Connection config record shape changed");
        };
    }
}

package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import redis.clients.jedis.JedisPooled;

class RedisCacheIdGeneratorTest {

    private static final String REDIS_IMAGE =
            "redis:8.2.1-alpine3.22@sha256:987c376c727652f99625c7d205a1cba3cb2c53b92b0b62aade2bd48ee1593232";

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);

    @Test
    void allocatesUniqueSequenceBlocksAcrossInstancesAndThreads() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try (JedisPooled jedis = jedis()) {
            String prefix = "cachedb:test:ids:" + UUID.randomUUID();
            RedisCacheIdGenerator first = new RedisCacheIdGenerator(jedis, prefix);
            RedisCacheIdGenerator second = new RedisCacheIdGenerator(jedis, prefix);
            Set<Long> ids = ConcurrentHashMap.newKeySet();
            ArrayList<Callable<Void>> tasks = new ArrayList<>();
            for (int task = 0; task < 8; task++) {
                RedisCacheIdGenerator generator = task % 2 == 0 ? first : second;
                tasks.add(
                        () -> {
                            for (int index = 0; index < 500; index++) {
                                ids.add(generator.nextSequence("orders", 64));
                            }
                            return null;
                        });
            }

            workers.invokeAll(tasks)
                    .forEach(
                            future -> {
                                try {
                                    future.get();
                                } catch (Exception exception) {
                                    throw new AssertionError(exception);
                                }
                            });

            assertEquals(4_000, ids.size());
            assertTrue(ids.stream().allMatch(id -> id > 0));
            long reservedEnd = Long.parseLong(jedis.get(prefix + ":orders"));
            assertTrue(reservedEnd >= ids.stream().mapToLong(Long::longValue).max().orElseThrow());
            assertTrue(
                    reservedEnd - ids.size() < 128,
                    "Only the two local allocation blocks may remain unused");
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(60)
    void concurrentVirtualThreadsNeverClaimOutsidePublishedBlocks() throws Exception {
        try (JedisPooled jedis = jedis();
                ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            int[] allocationSizes = {1, 2, 3, 64};
            for (int allocationSize : allocationSizes) {
                String prefix = "cachedb:test:ids:boundary:" + UUID.randomUUID();
                RedisCacheIdGenerator first = new RedisCacheIdGenerator(jedis, prefix);
                RedisCacheIdGenerator second = new RedisCacheIdGenerator(jedis, prefix);
                Set<Long> ids = ConcurrentHashMap.newKeySet();
                var start = new java.util.concurrent.CyclicBarrier(16);
                List<Callable<Void>> tasks = new ArrayList<>();
                for (int task = 0; task < 16; task++) {
                    RedisCacheIdGenerator generator = task % 2 == 0 ? first : second;
                    tasks.add(() -> {
                        start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        for (int index = 0; index < 500; index++) {
                            int requestedSize = allocationSize == 64 ? allocationSizes[index & 3] : allocationSize;
                            long id = generator.nextSequence("orders", requestedSize);
                            assertTrue(id > 0);
                            assertTrue(ids.add(id), "Duplicate sequence allocation");
                        }
                        return null;
                    });
                }
                for (var future : workers.invokeAll(tasks, 30, java.util.concurrent.TimeUnit.SECONDS)) {
                    future.get();
                }
                assertEquals(8_000, ids.size());
                long reservedEnd = Long.parseLong(jedis.get(prefix + ":orders"));
                assertTrue(reservedEnd >= ids.stream().mapToLong(Long::longValue).max().orElseThrow());
                assertTrue(reservedEnd - ids.size() < 2L * allocationSize);
            }
        }
    }

    @Test
    void exhaustedPositiveSequenceFailsWithoutWrappingOrChangingRedisCounter() {
        try (JedisPooled jedis = jedis()) {
            String prefix = "cachedb:test:ids:exhausted:" + UUID.randomUUID();
            RedisCacheIdGenerator generator = new RedisCacheIdGenerator(jedis, prefix);
            jedis.set(prefix + ":orders", Long.toString(Long.MAX_VALUE - 2L));
            assertEquals(Long.MAX_VALUE - 1L, generator.nextSequence("orders", 2));
            assertEquals(Long.MAX_VALUE, generator.nextSequence("orders", 2));
            for (int attempt = 0; attempt < 3; attempt++) {
                assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                        () -> generator.nextSequence("orders", 2));
                assertEquals(Long.toString(Long.MAX_VALUE), jedis.get(prefix + ":orders"));
            }
        }
    }

    @Test
    void createsMonotonicUlidsAndRejectsUnsafeSequenceInput() {
        try (JedisPooled jedis = jedis()) {
            RedisCacheIdGenerator generator = new RedisCacheIdGenerator(jedis, "cachedb:test:ulid");
            List<String> generated = new ArrayList<>(1_000);
            for (int index = 0; index < 1_000; index++) {
                generated.add(generator.nextUlid());
            }
            List<String> sorted = generated.stream().sorted(Comparator.naturalOrder()).toList();

            assertEquals(sorted, generated);
            assertEquals(1_000, Set.copyOf(generated).size());
            assertTrue(generated.stream().allMatch(value -> value.length() == 26));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> generator.nextSequence("orders;drop", 64));
            assertThrows(
                    IllegalArgumentException.class, () -> generator.nextSequence("orders", 10_001));
        }
    }

    @Test
    void cachedSequencesKeepValidationAllocationAndFailedRefillSemantics() {
        try (JedisPooled jedis = jedis()) {
            String prefix = "cachedb:test:buffer:" + UUID.randomUUID();
            RedisCacheIdGenerator generator = new RedisCacheIdGenerator(jedis, prefix);
            assertEquals(1, generator.nextSequence("orders", 4));
            assertEquals("4", jedis.get(prefix + ":orders"));
            assertThrows(IllegalArgumentException.class, () -> generator.nextSequence("orders", 0));
            assertThrows(
                    IllegalArgumentException.class, () -> generator.nextSequence("orders", 10_001));
            assertEquals(2, generator.nextSequence(new String("orders"), 10_000));
            assertEquals(3, generator.nextSequence("orders", 1));
            assertEquals(4, generator.nextSequence("orders", 2));
            assertEquals("4", jedis.get(prefix + ":orders"));
            assertEquals(5, generator.nextSequence("orders", 3));
            assertEquals("7", jedis.get(prefix + ":orders"));
            RedisCacheIdGenerator second = new RedisCacheIdGenerator(jedis, prefix);
            assertEquals(8, second.nextSequence("orders", 1));
            assertEquals(6, generator.nextSequence("orders", 1));
            assertEquals(7, generator.nextSequence("orders", 1));
            assertEquals(9, generator.nextSequence("orders", 1));

            assertEquals(1, generator.nextSequence("failure", 2));
            jedis.set(prefix + ":failure", "invalid-counter");
            assertEquals(2, generator.nextSequence("failure", 2));
            assertThrows(
                    redis.clients.jedis.exceptions.JedisDataException.class,
                    () -> generator.nextSequence("failure", 2));
            assertEquals("invalid-counter", jedis.get(prefix + ":failure"));
            jedis.set(prefix + ":failure", "2");
            assertEquals(3, generator.nextSequence("failure", 2));
            assertEquals("4", jedis.get(prefix + ":failure"));
        }
    }

    @Test
    void sequenceNamesKeepExactlyTheLegacyAsciiContract() {
        Pattern legacy = Pattern.compile("[A-Za-z0-9_.:-]+");
        Random random = new Random(140_024);
        try (JedisPooled jedis = jedis()) {
            RedisCacheIdGenerator generator =
                    new RedisCacheIdGenerator(jedis, "cachedb:test:names:" + UUID.randomUUID());
            assertThrows(IllegalArgumentException.class, () -> generator.nextSequence(null, 1));
            for (int run = 0; run < 1000; run++) {
                String alphabet = run % 2 == 0 ? "Az19_.:-" : "Az19_.:- ;/\n\u0130\u011f";
                StringBuilder name = new StringBuilder();
                for (int i = random.nextInt(20); i > 0; i--)
                    name.append(alphabet.charAt(random.nextInt(alphabet.length())));
                String value = name.toString();
                if (legacy.matcher(value).matches()) {
                    assertTrue(generator.nextSequence(value, 1) > 0);
                    assertTrue(generator.nextSequence(new String(value), 1) > 0);
                } else
                    assertThrows(
                            IllegalArgumentException.class, () -> generator.nextSequence(value, 1));
            }
            assertEquals(1, generator.nextSequence("a".repeat(600), 1));
        }
    }

    @org.junit.jupiter.api.BeforeAll
    static void startFixture() {
        if (System.getProperty("cachedb.snapshot.redis.host") == null) {
            org.junit.jupiter.api.Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(), "No explicit external Redis or Docker fixture");
            REDIS.start();
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void stopFixture() {
        if (System.getProperty("cachedb.snapshot.redis.host") == null && REDIS.isRunning()) REDIS.stop();
    }

    private JedisPooled jedis() {
        String host = System.getProperty("cachedb.snapshot.redis.host");
        return host == null ? new JedisPooled(REDIS.getHost(), REDIS.getMappedPort(6379))
                : new JedisPooled(host, Integer.getInteger("cachedb.snapshot.redis.port", 6379));
    }
}

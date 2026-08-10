package com.reactor.cachedb.redis;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class RedisCacheIdGeneratorTest {

    private static final String REDIS_IMAGE =
            "redis:8.2.1-alpine3.22@sha256:987c376c727652f99625c7d205a1cba3cb2c53b92b0b62aade2bd48ee1593232";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);

    @Test
    void allocatesUniqueSequenceBlocksAcrossInstancesAndThreads() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try (JedisPooled jedis = jedis()) {
            RedisCacheIdGenerator first = new RedisCacheIdGenerator(jedis, "cachedb:test:ids");
            RedisCacheIdGenerator second = new RedisCacheIdGenerator(jedis, "cachedb:test:ids");
            Set<Long> ids = ConcurrentHashMap.newKeySet();
            ArrayList<Callable<Void>> tasks = new ArrayList<>();
            for (int task = 0; task < 8; task++) {
                RedisCacheIdGenerator generator = task % 2 == 0 ? first : second;
                tasks.add(() -> {
                    for (int index = 0; index < 500; index++) {
                        ids.add(generator.nextSequence("orders", 64));
                    }
                    return null;
                });
            }

            workers.invokeAll(tasks).forEach(future -> {
                try {
                    future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });

            assertEquals(4_000, ids.size());
            assertTrue(ids.stream().allMatch(id -> id > 0));
            long reservedEnd = Long.parseLong(jedis.get("cachedb:test:ids:orders"));
            assertTrue(reservedEnd >= ids.stream().mapToLong(Long::longValue).max().orElseThrow());
            assertTrue(reservedEnd - ids.size() < 128, "Only the two local allocation blocks may remain unused");
        } finally {
            workers.shutdownNow();
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
            assertThrows(IllegalArgumentException.class, () -> generator.nextSequence("orders;drop", 64));
            assertThrows(IllegalArgumentException.class, () -> generator.nextSequence("orders", 10_001));
        }
    }

    private JedisPooled jedis() {
        return new JedisPooled(REDIS.getHost(), REDIS.getMappedPort(6379));
    }
}

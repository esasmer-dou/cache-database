package com.reactor.cachedb.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CacheDistributedJobExecutorTest {

    @Test
    void aSecondPodCanReadTheJobResultFromRedis() throws InterruptedException {
        JedisPooled jedis = sharedRedisMock();
        CacheDbSpringProperties.JobExecutorProperties properties = properties();

        try (CacheDistributedJobExecutor writer = executor(jedis, "pod-a", properties);
             CacheDistributedJobExecutor reader = executor(jedis, "pod-b", properties)) {
            CacheDistributedJobSnapshot accepted = writer.submit("customer-orders", () -> Map.of("rows", 25));

            CacheDistributedJobSnapshot completed = awaitTerminal(reader, accepted.jobId());

            assertEquals(CacheDistributedJobState.COMPLETED, completed.status());
            assertEquals("pod-a", completed.ownerInstanceId());
            assertEquals(25, completed.result().path("rows").asInt());
        }
    }

    @Test
    void rejectsExcessWorkInsteadOfGrowingAnUnboundedQueue() throws InterruptedException {
        JedisPooled jedis = sharedRedisMock();
        CacheDbSpringProperties.JobExecutorProperties properties = properties();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (CacheDistributedJobExecutor executor = executor(jedis, "pod-a", properties)) {
            executor.submit("blocking", () -> {
                started.countDown();
                await(release);
                return Map.of("done", true);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int index = 0; index < properties.getQueueCapacity(); index++) {
                executor.submit("queued-" + index, () -> Map.of("done", true));
            }

            CacheDistributedJobQueueFullException exception = assertThrows(
                    CacheDistributedJobQueueFullException.class,
                    () -> executor.submit("rejected", () -> Map.of("done", true))
            );
            assertTrue(exception.getMessage().contains("capacity=" + properties.getQueueCapacity()));
            release.countDown();
        } finally {
            release.countDown();
        }
    }

    private CacheDistributedJobSnapshot awaitTerminal(
            CacheDistributedJobExecutor executor,
            String jobId
    ) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<CacheDistributedJobSnapshot> snapshot = executor.find(jobId);
            if (snapshot.isPresent() && snapshot.get().status() == CacheDistributedJobState.COMPLETED) {
                return snapshot.get();
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Distributed job did not complete within the test timeout");
    }

    private CacheDistributedJobExecutor executor(
            JedisPooled jedis,
            String instanceId,
            CacheDbSpringProperties.JobExecutorProperties properties
    ) {
        return new CacheDistributedJobExecutor(jedis, new ObjectMapper(), instanceId, properties);
    }

    private CacheDbSpringProperties.JobExecutorProperties properties() {
        CacheDbSpringProperties.JobExecutorProperties properties = new CacheDbSpringProperties.JobExecutorProperties();
        properties.setWorkerThreads(1);
        properties.setQueueCapacity(8);
        properties.setShutdownAwaitMillis(1_000L);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private JedisPooled sharedRedisMock() {
        ConcurrentHashMap<String, ConcurrentHashMap<String, String>> hashes = new ConcurrentHashMap<>();
        JedisPooled jedis = mock(JedisPooled.class);
        when(jedis.hset(anyString(), anyMap())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Map<String, String> values = invocation.getArgument(1);
            hashes.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).putAll(values);
            return (long) values.size();
        });
        when(jedis.hgetAll(anyString())).thenAnswer(invocation -> {
            Map<String, String> values = hashes.get(invocation.getArgument(0));
            return values == null ? Map.of() : Map.copyOf(values);
        });
        when(jedis.expire(anyString(), anyInt())).thenReturn(1L);
        when(jedis.del(anyString())).thenAnswer(invocation ->
                hashes.remove(invocation.getArgument(0)) == null ? 0L : 1L);
        return jedis;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", exception);
        }
    }
}

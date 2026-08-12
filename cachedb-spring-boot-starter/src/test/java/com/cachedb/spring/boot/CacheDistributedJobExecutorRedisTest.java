package com.reactor.cachedb.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPooled;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class CacheDistributedJobExecutorRedisTest {
    private static final CacheDistributedJobDefinition<JobArguments> RESUMABLE_WARM =
            CacheDistributedJobDefinition.of("resumable-warm", JobArguments.class);

    private static final String REDIS_IMAGE =
            "redis:8.2.1-alpine3.22@sha256:987c376c727652f99625c7d205a1cba3cb2c53b92b0b62aade2bd48ee1593232";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no", "--maxmemory", "128mb",
                    "--maxmemory-policy", "noeviction");

    @Test
    void abandonedJobIsClaimedByAnotherPodAndResumesFromCheckpoint() throws Exception {
        CacheDbSpringProperties.JobExecutorProperties properties = properties("failover", 8);
        CountDownLatch firstAttemptFinished = new CountDownLatch(1);
        CacheDistributedJobHandler<JobArguments> failingHandler = handler((arguments, context) -> {
            context.checkpoint(Map.of("processedRows", 40));
            firstAttemptFinished.countDown();
            throw new IllegalStateException("pod-a terminated after checkpoint");
        });

        String jobId;
        try (JedisPooled jedis = jedis();
             CacheDistributedJobExecutor podA = executor(jedis, "pod-a", properties, failingHandler)) {
            jobId = podA.submit(RESUMABLE_WARM, new JobArguments(100)).jobId();
            assertTrue(firstAttemptFinished.await(5, TimeUnit.SECONDS));
            awaitSnapshot(podA, jobId, snapshot -> snapshot.status() == CacheDistributedJobState.QUEUED);
        }

        CacheDistributedJobHandler<JobArguments> resumingHandler = handler((arguments, context) -> {
            int processedRows = context.checkpoint()
                    .map(checkpoint -> checkpoint.path("processedRows").asInt())
                    .orElse(0);
            return Map.of(
                    "processedRows", arguments.rows(),
                    "resumedFrom", processedRows,
                    "attempt", context.attempt()
            );
        });

        try (JedisPooled jedis = jedis();
             CacheDistributedJobExecutor podB = executor(jedis, "pod-b", properties, resumingHandler)) {
            CacheDistributedJobSnapshot completed = awaitSnapshot(
                    podB,
                    jobId,
                    snapshot -> snapshot.status() == CacheDistributedJobState.COMPLETED
            );

            assertEquals("pod-b", completed.ownerInstanceId());
            assertEquals(100, completed.result().path("processedRows").asInt());
            assertEquals(40, completed.result().path("resumedFrom").asInt());
            assertEquals(2, completed.result().path("attempt").asInt());
        }
    }

    @Test
    void concurrentSubmittersCannotExceedTheRedisBackedCapacity() throws Exception {
        int capacity = 4;
        CacheDbSpringProperties.JobExecutorProperties properties = properties("capacity", capacity);
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        CacheDistributedJobHandler<JobArguments> blockingHandler = handler((arguments, context) -> {
            handlerStarted.countDown();
            assertTrue(releaseHandler.await(10, TimeUnit.SECONDS));
            return Map.of("processedRows", arguments.rows());
        });
        ExecutorService submitters = Executors.newFixedThreadPool(12);

        try (JedisPooled jedis = jedis();
             CacheDistributedJobExecutor executor = executor(jedis, "pod-a", properties, blockingHandler)) {
            List<Future<Boolean>> submissions = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                int rows = index + 1;
                submissions.add(submitters.submit(() -> {
                    try {
                        executor.submit(RESUMABLE_WARM, new JobArguments(rows));
                        return true;
                    } catch (CacheDistributedJobQueueFullException expected) {
                        return false;
                    }
                }));
            }

            AtomicInteger accepted = new AtomicInteger();
            for (Future<Boolean> submission : submissions) {
                if (submission.get(10, TimeUnit.SECONDS)) {
                    accepted.incrementAndGet();
                }
            }

            assertTrue(handlerStarted.await(5, TimeUnit.SECONDS));
            assertEquals(capacity, accepted.get());
            assertEquals(capacity, jedis.xlen(properties.getKeyPrefix() + ":stream"));
            releaseHandler.countDown();
        } finally {
            releaseHandler.countDown();
            submitters.shutdownNow();
        }
    }

    private CacheDistributedJobHandler<JobArguments> handler(JobOperation operation) {
        return CacheDistributedJobHandler.of(RESUMABLE_WARM, (arguments, context) -> {
            try {
                return operation.execute(arguments, context);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Job handler was interrupted", exception);
            }
        });
    }

    private CacheDistributedJobExecutor executor(
            JedisPooled jedis,
            String instanceId,
            CacheDbSpringProperties.JobExecutorProperties properties,
            CacheDistributedJobHandler<JobArguments> handler
    ) {
        return new CacheDistributedJobExecutor(jedis, new ObjectMapper(), instanceId, properties, List.of(handler));
    }

    private CacheDbSpringProperties.JobExecutorProperties properties(String suffix, int capacity) {
        CacheDbSpringProperties.JobExecutorProperties properties = new CacheDbSpringProperties.JobExecutorProperties();
        properties.setWorkerThreads(1);
        properties.setQueueCapacity(capacity);
        properties.setShutdownAwaitMillis(100L);
        properties.setClaimIdleMillis(1_000L);
        properties.setPollBlockMillis(100);
        properties.setRetryBackoffMillis(25L);
        properties.setMaxAttempts(3);
        properties.setKeyPrefix("test:cachedb:jobs:" + suffix);
        properties.setConsumerGroup("test-workers-" + suffix);
        return properties;
    }

    private JedisPooled jedis() {
        return new JedisPooled(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    private CacheDistributedJobSnapshot awaitSnapshot(
            CacheDistributedJobExecutor executor,
            String jobId,
            java.util.function.Predicate<CacheDistributedJobSnapshot> condition
    ) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        CacheDistributedJobSnapshot latest = null;
        while (System.nanoTime() < deadline) {
            Optional<CacheDistributedJobSnapshot> snapshot = executor.find(jobId);
            if (snapshot.isPresent()) {
                latest = snapshot.get();
                if (condition.test(latest)) {
                    return latest;
                }
            }
            Thread.sleep(25L);
        }
        assertNotNull(latest, "Job status was never stored");
        throw new AssertionError("Job did not reach the expected state; latest=" + latest.status());
    }

    private record JobArguments(int rows) {
    }

    @FunctionalInterface
    private interface JobOperation {
        Object execute(JobArguments arguments, CacheDistributedJobContext context) throws InterruptedException;
    }
}

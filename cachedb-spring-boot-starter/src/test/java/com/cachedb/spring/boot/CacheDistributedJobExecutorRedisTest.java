package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import redis.clients.jedis.JedisPooled;

class CacheDistributedJobExecutorRedisTest {
    private static final CacheDistributedJobDefinition<JobArguments> RESUMABLE_WARM =
            CacheDistributedJobDefinition.of("resumable-warm", JobArguments.class);

    private static final String REDIS_IMAGE =
            "redis:8.2.1-alpine3.22@sha256:987c376c727652f99625c7d205a1cba3cb2c53b92b0b62aade2bd48ee1593232";

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

    @Test
    void publisherStartsNoWorkersAndFullQueueLeavesNoOrphanState() {
        String unique = "client-only-" + java.util.UUID.randomUUID();
        var properties = properties(unique, 2);
        try (var redis = jedis(); var publisher = new CacheDistributedJobExecutor(
                com.reactor.cachedb.redis.JedisPooledOperations.of(redis), new ObjectMapper(), unique,
                properties, List.of(handler((arguments, context) -> Map.of())), false)) {
            publisher.submit(RESUMABLE_WARM, new JobArguments(1));
            publisher.submit(RESUMABLE_WARM, new JobArguments(2));
            org.junit.jupiter.api.Assertions.assertThrows(CacheDistributedJobQueueFullException.class,
                    () -> publisher.submit(RESUMABLE_WARM, new JobArguments(3)));
            assertEquals(2, redis.xlen(properties.getKeyPrefix()+":stream"));
            assertEquals(3, redis.keys(properties.getKeyPrefix()+":*").size());
            assertTrue(Thread.getAllStackTraces().keySet().stream()
                    .noneMatch(thread -> thread.getName().startsWith("cachedb-") && thread.getName().contains(unique)));
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="cachedb.redis.cluster", matches="true")
    void clusterPublisherRetainsCapacityAndCleanupForTaggedAndUntaggedKeys() {
        try (var cluster = TestFixtureSettings.cluster()) {
            for (boolean tagged : List.of(false,true)) {
                String unique = "cluster-publisher-"+java.util.UUID.randomUUID();
                var properties = properties(unique,2);
                properties.setKeyPrefix(tagged ? "test:{"+unique+"}:jobs" : "test:"+unique+":jobs");
                try (var publisher = new CacheDistributedJobExecutor(cluster,new ObjectMapper(),unique,properties,
                        List.of(handler((arguments,context) -> Map.of())),false)) {
                    var one = publisher.submit(RESUMABLE_WARM,new JobArguments(1));
                    var two = publisher.submit(RESUMABLE_WARM,new JobArguments(2));
                    org.junit.jupiter.api.Assertions.assertThrows(CacheDistributedJobQueueFullException.class,
                            () -> publisher.submit(RESUMABLE_WARM,new JobArguments(3)));
                    assertTrue(publisher.find(one.jobId()).isPresent());
                    assertTrue(publisher.find(two.jobId()).isPresent());
                    assertEquals(2,cluster.commands().xlen(properties.getKeyPrefix()+":stream"));
                    java.util.Set<String> owned = new java.util.HashSet<>();
                    String cursor = "0";
                    do {
                        var page = cluster.scan(cursor,new redis.clients.jedis.params.ScanParams().match(properties.getKeyPrefix()+":*").count(100));
                        owned.addAll(page.getResult()); cursor=page.getCursor();
                    } while (!"0".equals(cursor));
                    assertEquals(3,owned.size(),"The full queue must not leave an orphan job hash");
                    for (String key : owned) cluster.commands().unlink(key);
                }
            }
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

    @Test
    void producerTraceContextIsRestoredOnTheJobWorkerAndDoesNotLeak() throws Exception {
        var contexts = new java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>();
        var handler = handler((arguments, context) -> {
            contexts.add(com.reactor.cachedb.core.trace.TraceContexts.current()); return Map.of("ok", true);
        });
        try (var redis = jedis(); var executor = executor(redis, "traced", properties("traced", 4), handler)) {
            String first;
            try (var scope = com.reactor.cachedb.core.trace.TraceContexts.withMetadata(Map.of("correlationId", "command-42"))) {
                first = executor.submit(RESUMABLE_WARM, new JobArguments(1)).jobId();
            }
            awaitSnapshot(executor, first, job -> job.status() == CacheDistributedJobState.COMPLETED);
            String second = executor.submit(RESUMABLE_WARM, new JobArguments(2)).jobId();
            awaitSnapshot(executor, second, job -> job.status() == CacheDistributedJobState.COMPLETED);
            assertEquals("command-42", contexts.get(0).get("correlationId"));
            assertTrue(contexts.get(1).isEmpty());
            assertTrue(com.reactor.cachedb.core.trace.TraceContexts.current().isEmpty());
        }
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
        properties.setKeyPrefix("test:cachedb:jobs:" + suffix + ":" + java.util.UUID.randomUUID());
        properties.setConsumerGroup("test-workers-" + suffix);
        return properties;
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

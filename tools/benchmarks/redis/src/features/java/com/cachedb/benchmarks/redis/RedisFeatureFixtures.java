package com.reactor.cachedb.benchmarks.redis;

import com.reactor.cachedb.core.api.AsyncEntityRepository;
import com.reactor.cachedb.core.async.CacheAsyncExecutor;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.observe.*;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.spring.boot.observe.*;
import com.reactor.cachedb.starter.CacheDatabase;
import io.micrometer.observation.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.openjdk.jmh.infra.BenchmarkParams;
import redis.clients.jedis.Protocol.Command;

/** All qualification, Redis INFO and accounting snapshots are outside timed operations. */
final class RedisFeatureFixtures {
    private RedisFeatureFixtures() { }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    static void await(BooleanSupplier condition, long millis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && until - System.nanoTime() > 0) Thread.sleep(2);
        require(condition.getAsBoolean(), "Bounded feature qualification deadline exceeded");
    }
    static String mode(BenchmarkParams params) {
        require(params.getThreads() == 1, "Feature probes require exactly one platform JMH caller");
        require(params.getMode() == org.openjdk.jmh.annotations.Mode.Throughput
                || params.getMode() == org.openjdk.jmh.annotations.Mode.SampleTime, "Unexpected mode");
        return params.getMode() == org.openjdk.jmh.annotations.Mode.Throughput ? "thrpt" : "sample";
    }
    static void platform() {
        require(!Thread.currentThread().isVirtual(), "Feature caller must be platform, not a hidden VT offload");
    }
    static String common() {
        int cpus = Runtime.getRuntime().availableProcessors();
        require(cpus == 1 || cpus == 2, "Unexpected child CPU count");
        long heap = Runtime.getRuntime().maxMemory();
        require(heap >= 480L * 1024 * 1024 && heap <= 512L * 1024 * 1024, "Child heap is not bounded at 512 MiB");
        return " cpus=" + cpus + " fairness=true maxTotal=32 maxWaitMillis=10000"
                + " connectMillis=5000 socketMillis=10000 blockingMillis=15000 source=REDIS_ONLY guard=UNCHANGED";
    }
    static RedisBenchmarkFixture create(boolean near, boolean writes, CacheRuntimeOptions options) throws Exception {
        var f = new RedisBenchmarkFixture(1000, false, near, writes,
                (redis, source, config) -> {
                    require(config.readThrough().mode() == ReadThroughMode.REDIS_ONLY
                            && config.redisGuardrail().equals(RedisGuardrailConfig.defaults()), "Source/guard changed");
                    return new CacheDatabase(redis, source, config, options);
                }, (database, operations, keys) -> database.nearCacheCoordinator());
        try {
            require(f.database.runtimeOptions() == options && f.database.redisReadRuntime() != null, "Options not retained");
            require(near ? f.coordinator == f.database.nearCacheCoordinator() && f.store != null
                    : f.coordinator == null && f.store == null, "Coordinator must be the sole borrowed database owner");
            var pool = f.redis.getPool();
            require(pool.getFairness() && pool.getMaxTotal() == 32 && pool.getMaxIdle() == 32
                    && pool.getMinIdle() == 0 && pool.getMaxWaitDuration().equals(Duration.ofSeconds(10))
                    && new JedisPooledOperations(f.redis).physicalPoolCapacity() == 32, "Actual pool parity failed");
            try (var connection = pool.getResource()) {
                require(connection.getSoTimeout() == 10000, "Actual ordinary socket timeout changed");
            }
            return f;
        } catch (RuntimeException | Error failure) {
            try { f.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    static CacheRuntimeOptions.Builder options(boolean coalescing) {
        return CacheRuntimeOptions.builder().redisReadCoalescing(
                RedisReadCoalescingConfig.builder().enabled(coalescing).build());
    }
    static NearCacheInvalidationConfig invalidation(NearCacheInvalidationMode mode) {
        require(mode == NearCacheInvalidationMode.PUBSUB || mode == NearCacheInvalidationMode.SERVER_TRACKING, "Explicit modes only");
        return NearCacheInvalidationConfig.builder().mode(mode)
                .publishLegacyInvalidations(mode == NearCacheInvalidationMode.PUBSUB).build();
    }
    static void verify(RedisBenchmarkFixture.Row row, long id) {
        RedisCoalescingFixtures.verify(row, id);
    }
    static void noReads(RedisCoalescingFixtures.Delta d) {
        require(d.batches() == 0 && d.coalesced() == 0 && d.disabled() == 0 && d.causal() == 0
                && d.unitOfWork() == 0 && d.transportBypassed() == 0, "L1 hit performed backing point-read I/O");
    }
    static void warm(RedisBenchmarkFixture f) throws Exception {
        await(f.coordinator::connected, 10000);
        // Drain seed/write invalidations and prewarm the same 100 L1 rows outside timing.
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        long stable = System.nanoTime(), previous = f.store.epoch();
        for (;;) {
            for (long id = 1; id <= 100; id++) f.repository.findById(id).orElseThrow();
            long now = System.nanoTime(), epoch = f.store.epoch();
            boolean complete = true;
            for (long id = 1; id <= 100; id++) complete &= f.store.lookup(Long.toString(id), 0) != null;
            if (!complete || epoch != previous || !f.coordinator.connected()) stable = now;
            previous = epoch;
            if (complete && now - stable >= TimeUnit.MILLISECONDS.toNanos(250)) return;
            require(until - now > 0, "L1 never reached a stable admitted generation");
            Thread.sleep(5);
        }
    }
    record NearState(long flushes, long reconnects, Map<String, List<Long>> ids, long epoch) {
        static NearState capture(RedisBenchmarkFixture f, NearCacheInvalidationMode expected) {
            boolean tracking = expected == NearCacheInvalidationMode.SERVER_TRACKING;
            require(f.coordinator == f.database.nearCacheCoordinator() && f.coordinator.connected()
                    && f.database.nearCacheInvalidationMode() == expected
                    && f.database.nearCacheTrackingReadySupplier().getAsBoolean() == tracking
                    && f.database.publishLegacyNearInvalidations() == !tracking, "Actual invalidation mode/readiness/publication mismatch");
            var ids = f.coordinator.trackingClientIds();
            require(tracking ? !ids.isEmpty() && ids.values().stream().allMatch(v -> v.size() == 2
                    && v.get(0) > 0 && v.get(1) > 0 && !v.get(0).equals(v.get(1))) : ids.isEmpty(),
                    "Missing dedicated tracking subscriber/control IDs");
            return new NearState(f.coordinator.flushAllEvents(), f.coordinator.trackingReconnects(), ids, f.store.epoch());
        }
        String check(RedisBenchmarkFixture f, NearCacheInvalidationMode expected, boolean hit) {
            var after = capture(f, expected);
            require(after.flushes == flushes && after.reconnects == reconnects && after.ids.equals(ids),
                    "A disconnected/reconnected/fallback generation cannot qualify");
            if (hit) require(after.epoch == epoch, "L1 hit fixture invalidated during the iteration");
            return " actualMode=" + expected + " connected=true tracking="
                    + (expected == NearCacheInvalidationMode.SERVER_TRACKING)
                    + " publishLegacy=" + (expected == NearCacheInvalidationMode.PUBSUB)
                    + " flushDelta=0 reconnectDelta=0 generationStable=true";
        }
    }
    static long publishes(RedisBenchmarkFixture f) {
        Object raw = f.redis.sendCommand(Command.INFO, "commandstats");
        String info = raw instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : raw.toString();
        for (String line : info.split("\\r?\\n")) {
            if (!line.startsWith("cmdstat_publish:")) continue;
            for (String part : line.substring(line.indexOf(':') + 1).split(","))
                if (part.startsWith("calls=")) return Long.parseLong(part.substring(6));
            throw new IllegalStateException("Malformed INFO commandstats PUBLISH");
        }
        return 0;
    }
    static void publication(NearCacheInvalidationMode mode, long writes, long delta) {
        require(mode == NearCacheInvalidationMode.SERVER_TRACKING ? delta == 0 : delta == writes,
                "Actual INFO PUBLISH delta does not match every foreground write; use an exclusive Redis server");
    }

    static final class ObservationChoice {
        final ObservationRegistry registry;
        final CacheObservationRecorder recorder;
        ObservationChoice(String variant) {
            switch (variant) {
                case "disabled" -> { registry = ObservationRegistry.NOOP; recorder = NoOpCacheObservationRecorder.INSTANCE; }
                case "noop" -> { registry = ObservationRegistry.NOOP; recorder = new MicrometerCacheObservationRecorder(registry); }
                case "handler" -> {
                    registry = ObservationRegistry.create();
                    registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
                        @Override public boolean supportsContext(Observation.Context context) {
                            return context instanceof CacheMicrometerObservationContext;
                        }
                    });
                    recorder = new MicrometerCacheObservationRecorder(registry);
                }
                default -> throw new IllegalArgumentException("Unknown observation variant");
            }
            require(recorder.enabled() == variant.equals("handler"), "Wrong observation enabled state");
            if (variant.equals("noop")) require(recorder instanceof MicrometerCacheObservationRecorder
                    && registry == ObservationRegistry.NOOP, "Must measure the real Micrometer NOOP adapter");
        }
        void check() {
            require(registry.getCurrentObservation() == null, "Observation scope leaked onto the caller");
            if (recorder instanceof MicrometerCacheObservationRecorder micrometer)
                require(micrometer.instrumentationFailures() == 0, "Observation handler failure was suppressed");
        }
    }
    static final class Probe implements ObservationHandler<CacheMicrometerObservationContext> {
        int starts, stops;
        CacheMicrometerObservationContext last;
        @Override public boolean supportsContext(Observation.Context context) {
            return context instanceof CacheMicrometerObservationContext;
        }
        @Override public void onStart(CacheMicrometerObservationContext context) { starts++; }
        @Override public void onStop(CacheMicrometerObservationContext context) { stops++; last = context; }
        void reset() { starts = stops = 0; last = null; }
        void verify(String tier) {
            require(starts == 1 && stops == 1 && last != null && last.operation() == CacheOperation.FIND_BY_ID
                    && last.getError() == null, "Expected exactly one real native findById observation");
            require(tier.equals(last.getLowCardinalityKeyValue("cachedb.tier").getValue())
                    && "hit".equals(last.getLowCardinalityKeyValue("cachedb.outcome").getValue()), "Wrong native tier/outcome");
        }
    }
    static final class ForbiddenOffload implements java.util.concurrent.Executor {
        final AtomicInteger executions = new AtomicInteger();
        @Override public void execute(Runnable task) {
            executions.incrementAndGet();
            throw new RejectedExecutionException("Native point read attempted worker offload");
        }
    }
    record AsyncState(CacheAsyncExecutor.Snapshot worker, CacheAsyncExecutor.ExternalSnapshot external,
                      RedisRepositoryReadState.Metrics reads) {
        static AsyncState capture(RedisBenchmarkFixture f) {
            return new AsyncState(f.database.asyncExecutor().snapshot(), f.database.asyncExecutor().externalSnapshot(),
                    f.database.redisReadMetrics());
        }
        String check(RedisBenchmarkFixture f, ForbiddenOffload offload, boolean enabled, long total) throws Exception {
            awaitIdle(f);
            var after = capture(f);
            var d = RedisCoalescingFixtures.Delta.between(reads, after.reads);
            require(total > 0 && offload.executions.get() == 0 && after.worker.accepted() == worker.accepted()
                    && after.worker.rejected() == worker.rejected() && after.worker.failed() == worker.failed()
                    && after.worker.cancelled() == worker.cancelled() && after.worker.completed() == worker.completed()
                    && !after.worker.closed() && after.worker.outstandingDispatches() == 0
                    && after.worker.availablePermits() == after.worker.maxConcurrent(), "Native path offloaded or retained permits");
            long accepted = after.external.accepted() - external.accepted();
            long completed = after.external.completed() - external.completed();
            require(accepted == total && completed == total && after.external.outstanding() == 0
                    && after.external.rejected() == external.rejected() && after.external.failed() == external.failed()
                    && after.external.cancelled() == external.cancelled(), "Native admissions/completions diverged from real requests");
            require(d.disabled() == 0 && d.causal() == 0 && d.unitOfWork() == 0 && d.batches() > 0
                    && d.batches() + d.coalesced() == total
                    && (enabled ? d.transportBypassed() == 0 && d.coalesced() > 0
                    : d.transportBypassed() == total && d.coalesced() == 0 && d.batches() == total),
                    "Native disabled FIFO/enabled coalescing accounting failed");
            return d.fields() + " externalAccepted=" + accepted + " externalCompleted=" + completed
                    + " externalRejected=0 externalFailed=0 externalCancelled=0 offload=0"
                    + " outstanding=0 reservedBytes=0 deliveries=0 expiryWorkers=0";
        }
    }
    static void awaitIdle(RedisBenchmarkFixture f) throws Exception {
        require(f.database.asyncExecutor().awaitIdle(Duration.ofSeconds(5)), "Native physical I/O did not terminate");
        await(() -> {
            var m = f.database.nativeReadMemory();
            return m.reservedBytes() == 0 && m.deliveries() == 0 && m.expiryWorkers() == 0;
        }, 5000);
    }
    @SuppressWarnings("unchecked")
    static CompletableFuture<Optional<RedisBenchmarkFixture.Row>>[] futures() {
        return (CompletableFuture<Optional<RedisBenchmarkFixture.Row>>[]) new CompletableFuture<?>[1000];
    }
    /** One real nonblocking fan-out window, one bounded caller wait, no per-request worker. */
    static long window(AsyncEntityRepository<RedisBenchmarkFixture.Row, Long> repository,
            CompletableFuture<Optional<RedisBenchmarkFixture.Row>>[] futures) throws Exception {
        try {
            for (int i = 0; i < futures.length; i++) futures[i] = repository.findByIdAsync(i + 1L);
            CompletableFuture.allOf(futures).get(15, TimeUnit.SECONDS);
            long checksum = 0;
            for (int i = 0; i < futures.length; i++) {
                var row = futures[i].join().orElseThrow();
                verify(row, i + 1L); checksum += row.id();
            }
            require(checksum == 500500, "Native asynchronous full-row parity failed");
            return checksum;
        } finally {
            for (var future : futures) if (future != null && !future.isDone()) future.cancel(false);
            Arrays.fill(futures, null);
        }
    }
}

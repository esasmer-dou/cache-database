package com.reactor.cachedb.benchmarks.redis;

import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.spring.boot.observe.MicrometerCacheObservationRecorder;
import io.micrometer.observation.ObservationRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real provider controls only. No JMH throughput/latency acceptance is inferred here. */
public final class RedisFeaturePreflight {
    private RedisFeaturePreflight() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Choose observation, async or tracking");
        RedisFeatureFixtures.platform();
        boolean d = RedisReadCoalescingConfig.defaults().enabled();
        RedisFeatureFixtures.require(d == RedisReadCoalescingConfig.builder().build().enabled()
                && d == CacheRuntimeOptions.defaults().redisReadCoalescing().enabled(), "Artifact defaults disagree");
        System.out.println("T9_DEFAULT coalescing=" + d + " configBuilder=" + d + " runtimeOptions=" + d);
        switch (args[0]) {
            case "observation" -> observation();
            case "async" -> nativeAsync();
            case "tracking" -> tracking();
            default -> throw new IllegalArgumentException("Unknown feature group");
        }
    }
    private static void observation() throws Exception {
        for (boolean hit : new boolean[]{true, false}) {
            String path = hit ? "hit" : "miss";
            for (String variant : new String[]{"disabled", "noop", "handler"}) {
                var choice = new RedisFeatureFixtures.ObservationChoice(variant);
                var options = RedisFeatureFixtures.options(false).observationRecorder(choice.recorder)
                        .nearCacheInvalidation(RedisFeatureFixtures.invalidation(NearCacheInvalidationMode.PUBSUB)).build();
                try (var f = RedisFeatureFixtures.create(hit, false, options)) {
                    if (hit) RedisFeatureFixtures.warm(f);
                    var before = f.database.redisReadMetrics();
                    RedisFeatureFixtures.verify(f.repository.findById(1L).orElseThrow(), 1);
                    var delta = RedisCoalescingFixtures.Delta.between(before, f.database.redisReadMetrics());
                    if (hit) RedisFeatureFixtures.noReads(delta); else delta.require(false, 1, false);
                    choice.check();
                    System.out.println("T9_PREFLIGHT feature=observation path=" + path + " variant=" + variant
                            + " total=1 checksum=1 observationEnabled=" + choice.recorder.enabled()
                            + " instrumentationFailures=0 scopeLeaked=false" + delta.fields() + RedisFeatureFixtures.common());
                }
            }
            var registry = ObservationRegistry.create();
            var probe = new RedisFeatureFixtures.Probe();
            registry.observationConfig().observationHandler(probe);
            var recorder = new MicrometerCacheObservationRecorder(registry);
            var options = RedisFeatureFixtures.options(false).observationRecorder(recorder)
                    .nearCacheInvalidation(RedisFeatureFixtures.invalidation(NearCacheInvalidationMode.PUBSUB)).build();
            try (var f = RedisFeatureFixtures.create(hit, false, options)) {
                if (hit) RedisFeatureFixtures.warm(f);
                probe.reset();
                RedisFeatureFixtures.verify(f.repository.findById(1L).orElseThrow(), 1);
                probe.verify(hit ? "L1" : "REDIS");
                RedisFeatureFixtures.require(registry.getCurrentObservation() == null && recorder.instrumentationFailures() == 0,
                        "Native observation positive control leaked/failed");
                System.out.println("T9_OBSERVATION_CONTROL path=" + path + " scopes=1 nativeHook=true tier="
                        + (hit ? "L1" : "REDIS") + " outcome=hit instrumentationFailures=0 scopeLeaked=false");
            }
        }
    }
    private static void nativeAsync() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            var offload = new RedisFeatureFixtures.ForbiddenOffload();
            var options = RedisFeatureFixtures.options(enabled).asyncExecutor(offload).asyncMaxConcurrent(1024).build();
            try (var f = RedisFeatureFixtures.create(false, false, options)) {
                var repository = f.repository.async();
                var futures = RedisFeatureFixtures.futures();
                var before = RedisFeatureFixtures.AsyncState.capture(f);
                long checksum = 0, until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                for (int round = 0; round < 16; round++) {
                    RedisFeatureFixtures.require(until - System.nanoTime() > 0, "Native async preflight total deadline exceeded");
                    checksum += RedisFeatureFixtures.window(repository, futures);
                }
                RedisFeatureFixtures.require(checksum == 8008000, "Both native toggles must return identical full rows");
                String counters = before.check(f, offload, enabled, 16000);
                System.out.println("T9_PREFLIGHT feature=async path=miss variant=" + enabled
                        + " workers=1 threads=1 virtual=false logicalInFlight=1000 operationsPerInvocation=1000"
                        + " min=1000 total=16000 checksum=" + checksum + counters + RedisFeatureFixtures.common());
            }
        }
    }
    private static void tracking() throws Exception {
        boolean rejected = false;
        try { NearCacheInvalidationConfig.builder().mode(NearCacheInvalidationMode.AUTO).publishLegacyInvalidations(false).build(); }
        catch (IllegalArgumentException expected) { rejected = true; }
        RedisFeatureFixtures.require(rejected, "Mixed-fleet AUTO plus legacy opt-out must fail closed");
        System.out.println("T9_FLEET_CONTROL rejectedAutoLegacyFalse=true strictOnly=true");
        for (var mode : new NearCacheInvalidationMode[]{NearCacheInvalidationMode.PUBSUB, NearCacheInvalidationMode.SERVER_TRACKING}) {
            var options = RedisFeatureFixtures.options(false).nearCacheInvalidation(RedisFeatureFixtures.invalidation(mode)).build();
            try (var f = RedisFeatureFixtures.create(true, true, options)) {
                RedisFeatureFixtures.warm(f);
                int rawInvalidation = 0;
                if (mode == NearCacheInvalidationMode.SERVER_TRACKING) {
                    var keys = new RedisKeyStrategy(f.prefix);
                    var changed = new RedisBenchmarkFixture.Row(1L, "tenant-a", "group-1", -1);
                    f.redis.set(keys.entityKey("benchmark_rows", 1), new RedisBenchmarkFixture.Codec().toRedisValue(changed));
                    RedisFeatureFixtures.await(() -> f.store.lookup("1", 0) == null, 1000);
                    RedisFeatureFixtures.require(f.repository.findById(1L).orElseThrow().amount() == -1,
                            "A raw Jedis write did not invalidate the actual database L1");
                    f.repository.save(new RedisBenchmarkFixture.Row(1L, "tenant-a", "group-1", 1));
                    RedisFeatureFixtures.warm(f); rawInvalidation = 1;
                }
                var health = RedisFeatureFixtures.NearState.capture(f, mode);
                long before = RedisFeatureFixtures.publishes(f), received = f.coordinator.invalidationsReceived();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                for (long id = 1001; id <= 2000; id++) {
                    RedisFeatureFixtures.require(until - System.nanoTime() > 0 && f.coordinator.connected()
                            && f.database.nearCacheInvalidationMode() == mode, "Tracking write proof lost readiness/deadline");
                    f.repository.save(new RedisBenchmarkFixture.Row(id, "tenant-a", "group-" + id % 20, id));
                }
                RedisFeatureFixtures.await(() -> f.coordinator.invalidationsReceived() - received >= 1000, 5000);
                String actual = health.check(f, mode, false);
                long delta = RedisFeatureFixtures.publishes(f) - before;
                RedisFeatureFixtures.publication(mode, 1000, delta);
                var keys = new RedisKeyStrategy(f.prefix);
                for (int start = 1001; start <= 2000; start += 256) {
                    var versions = new ArrayList<String>();
                    for (long id = start; id <= Math.min(2000, start + 255); id++) versions.add(keys.versionKey("benchmark_rows", id));
                    var values = f.redis.mget(versions.toArray(String[]::new));
                    RedisFeatureFixtures.require(values.size() == versions.size() && values.stream().allMatch("1"::equals),
                            "Every one of the thousand CacheDB writes must really exist at version one");
                }
                RedisFeatureFixtures.verify(f.repository.findById(2000L).orElseThrow(), 2000);
                System.out.println("T9_PREFLIGHT feature=tracking path=write variant=" + mode
                        + " total=1000 versions=1000 publishDelta=" + delta + " rawInvalidation=" + rawInvalidation
                        + actual + RedisFeatureFixtures.common());
            }
        }
    }
}

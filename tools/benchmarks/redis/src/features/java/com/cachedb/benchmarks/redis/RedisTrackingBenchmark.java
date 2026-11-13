package com.reactor.cachedb.benchmarks.redis;

import com.reactor.cachedb.core.config.NearCacheInvalidationMode;
import com.reactor.cachedb.redis.RedisRepositoryReadState;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.*;

@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 5, time = 3)
public class RedisTrackingBenchmark {
    @State(Scope.Benchmark)
    public static class Data {
        @Param({"PUBSUB", "SERVER_TRACKING"}) public String invalidation;
        RedisBenchmarkFixture fixture;
        NearCacheInvalidationMode expected;
        RedisFeatureFixtures.NearState near;
        RedisRepositoryReadState.Metrics reads;
        boolean hit;
        String mode;
        long value, total, publishBefore, invalidationsBefore;
        long sequence = 1000000;
        int ordinal;
        @Setup(Level.Trial) public void open(BenchmarkParams params) throws Exception {
            mode = RedisFeatureFixtures.mode(params); expected = NearCacheInvalidationMode.valueOf(invalidation);
            hit = params.getBenchmark().endsWith(".l1HitFindById");
            var options = RedisFeatureFixtures.options(false).nearCacheInvalidation(RedisFeatureFixtures.invalidation(expected)).build();
            fixture = RedisFeatureFixtures.create(true, !hit, options);
            RedisFeatureFixtures.NearState.capture(fixture, expected);
            System.out.println(fields("T9_TRIAL") + RedisFeatureFixtures.common());
        }
        String fields(String prefix) {
            return prefix + " pid=" + ProcessHandle.current().pid() + " feature=tracking path="
                    + (hit ? "hit" : "write") + " variant=" + invalidation + " mode=" + mode + " threads=1 virtual=false";
        }
        @Setup(Level.Iteration) public void begin() throws Exception {
            RedisFeatureFixtures.platform();
            RedisFeatureFixtures.warm(fixture);
            near = RedisFeatureFixtures.NearState.capture(fixture, expected);
            reads = fixture.database.redisReadMetrics(); value = total = 0;
            invalidationsBefore = fixture.coordinator.invalidationsReceived();
            publishBefore = RedisFeatureFixtures.publishes(fixture);
        }
        long next() { value = value % (hit ? 100 : 1000) + 1; return value; }
        @TearDown(Level.Iteration) public void end() throws Exception {
            RedisFeatureFixtures.platform();
            RedisFeatureFixtures.require(total > 0, "The platform worker made no progress");
            if (!hit) RedisFeatureFixtures.await(
                    () -> fixture.coordinator.invalidationsReceived() - invalidationsBefore >= total, 5000);
            String health = near.check(fixture, expected, hit);
            long publishDelta = RedisFeatureFixtures.publishes(fixture) - publishBefore;
            String readProof = "";
            if (hit) {
                RedisFeatureFixtures.require(publishDelta == 0, "L1 hit caused publication");
                var delta = RedisCoalescingFixtures.Delta.between(reads, fixture.database.redisReadMetrics());
                RedisFeatureFixtures.noReads(delta); readProof = delta.fields();
            } else RedisFeatureFixtures.publication(expected, total, publishDelta);
            System.out.println(fields("T9_ITER") + " ordinal=" + ordinal++ + " min=" + total + " total=" + total
                    + " publishDelta=" + publishDelta + readProof + health);
        }
        @TearDown(Level.Trial) public void close() { if (fixture != null) fixture.close(); }
    }
    @Benchmark public void l1HitFindById(Data state, Blackhole sink) {
        sink.consume(state.fixture.repository.findById(state.next()).orElseThrow()); state.total++;
    }
    @Benchmark public void saveSingle(Data state, Blackhole sink) {
        long id = state.next();
        sink.consume(state.fixture.repository.save(new RedisBenchmarkFixture.Row(id, "tenant-a", "group-" + id % 20, ++state.sequence)));
        state.total++;
    }
}

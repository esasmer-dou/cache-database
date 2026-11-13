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
public class RedisObservationBenchmark {
    @State(Scope.Benchmark)
    public static class Data {
        @Param({"disabled", "noop", "handler"}) public String observation;
        RedisBenchmarkFixture fixture;
        RedisFeatureFixtures.ObservationChoice choice;
        RedisFeatureFixtures.NearState near;
        RedisRepositoryReadState.Metrics before;
        boolean hit;
        String mode;
        long value, total;
        int ordinal;
        @Setup(Level.Trial) public void open(BenchmarkParams params) throws Exception {
            mode = RedisFeatureFixtures.mode(params);
            hit = params.getBenchmark().endsWith(".l1HitFindById");
            choice = new RedisFeatureFixtures.ObservationChoice(observation);
            var options = RedisFeatureFixtures.options(false).observationRecorder(choice.recorder)
                    .nearCacheInvalidation(RedisFeatureFixtures.invalidation(NearCacheInvalidationMode.PUBSUB)).build();
            fixture = RedisFeatureFixtures.create(hit, false, options);
            RedisFeatureFixtures.require(fixture.database.runtimeOptions().observationRecorder() == choice.recorder,
                    "Real native observation recorder was not retained");
            System.out.println(fields("T9_TRIAL") + RedisFeatureFixtures.common());
        }
        String fields(String prefix) {
            return prefix + " pid=" + ProcessHandle.current().pid() + " feature=observation path="
                    + (hit ? "hit" : "miss") + " variant=" + observation + " mode=" + mode + " threads=1 virtual=false";
        }
        @Setup(Level.Iteration) public void begin() throws Exception {
            RedisFeatureFixtures.platform();
            if (hit) {
                RedisFeatureFixtures.warm(fixture);
                near = RedisFeatureFixtures.NearState.capture(fixture, NearCacheInvalidationMode.PUBSUB);
            } else {
                for (long id = 1; id <= 1000; id++) RedisFeatureFixtures.verify(fixture.repository.findById(id).orElseThrow(), id);
            }
            choice.check(); value = total = 0;
            before = fixture.database.redisReadMetrics();
        }
        long next() { value = value % (hit ? 100 : 1000) + 1; return value; }
        @TearDown(Level.Iteration) public void end() {
            RedisFeatureFixtures.platform();
            RedisFeatureFixtures.require(total > 0, "The platform worker made no progress");
            var d = RedisCoalescingFixtures.Delta.between(before, fixture.database.redisReadMetrics());
            String health = "";
            if (hit) {
                RedisFeatureFixtures.noReads(d);
                health = near.check(fixture, NearCacheInvalidationMode.PUBSUB, true);
            } else d.require(false, total, false);
            choice.check();
            System.out.println(fields("T9_ITER") + " ordinal=" + ordinal++ + " min=" + total + " total=" + total
                    + d.fields() + " observationEnabled=" + choice.recorder.enabled() + " instrumentationFailures=0 scopeLeaked=false" + health);
        }
        @TearDown(Level.Trial) public void close() { if (fixture != null) fixture.close(); }
    }
    @Benchmark public void l1HitFindById(Data state, Blackhole sink) {
        sink.consume(state.fixture.repository.findById(state.next()).orElseThrow()); state.total++;
    }
    @Benchmark public void l1MissFindById(Data state, Blackhole sink) {
        sink.consume(state.fixture.repository.findById(state.next()).orElseThrow()); state.total++;
    }
}

package com.reactor.cachedb.benchmarks.redis;

import com.reactor.cachedb.core.api.AsyncEntityRepository;
import java.util.Optional;
import java.util.concurrent.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.*;

@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 5, time = 3)
public class RedisNativeAsyncBenchmark {
    @State(Scope.Benchmark)
    public static class Data {
        @Param({"false", "true"}) public boolean coalescing;
        RedisBenchmarkFixture fixture;
        AsyncEntityRepository<RedisBenchmarkFixture.Row, Long> repository;
        CompletableFuture<Optional<RedisBenchmarkFixture.Row>>[] futures;
        RedisFeatureFixtures.ForbiddenOffload offload;
        RedisFeatureFixtures.AsyncState before;
        String mode;
        long total;
        int ordinal;
        @Setup(Level.Trial) public void open(BenchmarkParams params) throws Exception {
            mode = RedisFeatureFixtures.mode(params);
            offload = new RedisFeatureFixtures.ForbiddenOffload();
            var options = RedisFeatureFixtures.options(coalescing).asyncExecutor(offload).asyncMaxConcurrent(1024).build();
            fixture = RedisFeatureFixtures.create(false, false, options);
            repository = fixture.repository.async();
            futures = RedisFeatureFixtures.futures();
            System.out.println(fields("T9_TRIAL") + RedisFeatureFixtures.common());
        }
        String fields(String prefix) {
            return prefix + " pid=" + ProcessHandle.current().pid()
                    + " feature=async path=miss variant=" + coalescing + " mode=" + mode
                    + " threads=1 virtual=false logicalInFlight=1000 operationsPerInvocation=1000";
        }
        @Setup(Level.Iteration) public void begin() throws Exception {
            RedisFeatureFixtures.platform();
            RedisFeatureFixtures.window(repository, futures);
            RedisFeatureFixtures.awaitIdle(fixture);
            before = RedisFeatureFixtures.AsyncState.capture(fixture); total = 0;
        }
        @TearDown(Level.Iteration) public void end() throws Exception {
            RedisFeatureFixtures.platform();
            String proof = before.check(fixture, offload, coalescing, total);
            System.out.println(fields("T9_ITER") + " ordinal=" + ordinal++ + " min=" + total + " total=" + total + proof);
        }
        @TearDown(Level.Trial) public void close() { if (fixture != null) fixture.close(); }
    }
    /** SampleTime is window latency divided by 1000, NOT a per-request response-time distribution. */
    @Benchmark @OperationsPerInvocation(1000)
    public void nativeL1MissWindow1000(Data state, Blackhole sink) throws Exception {
        sink.consume(RedisFeatureFixtures.window(state.repository, state.futures)); state.total += 1000;
    }
}

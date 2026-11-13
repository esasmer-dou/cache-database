package com.reactor.cachedb.benchmarks.redis;

import com.reactor.cachedb.redis.RedisRepositoryReadState;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.ThreadParams;

@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 5, time = 3)
public class RedisCoalescingBenchmark {
    @State(Scope.Benchmark)
    public static class Shared {
        @Param({"false", "true"}) public boolean coalescing;
        RedisBenchmarkFixture fixture;
        Identity[] workers;
        boolean virtual;
        String mode;
        int ordinal;
        RedisRepositoryReadState.Metrics before;

        @Setup(Level.Trial) public void open(BenchmarkParams params) throws Exception {
            int threads = params.getThreads();
            virtual = params.getBenchmark().endsWith("l1MissFindByIdVirtual1000");
            if (virtual ? threads != 1000 : threads != 1 && threads != 16 && threads != 64)
                throw new IllegalArgumentException("Fixed T1 worker matrix is 1/16/64 platform or 1000 virtual");
            mode = params.getMode() == Mode.Throughput ? "thrpt" : "sample";
            workers = new Identity[threads];
            fixture = RedisCoalescingFixtures.create(coalescing);
            // Same complete seeded data and sequential prewarm for both toggles, never source reads.
            for (long id = 1; id <= 1000; id++)
                RedisCoalescingFixtures.verify(fixture.repository.findById(id).orElseThrow(), id);
            System.out.println("T1_TRIAL pid=" + ProcessHandle.current().pid() + " coalescing=" + coalescing
                    + " mode=" + mode + " threads=" + threads + " virtual=" + virtual);
        }
        @Setup(Level.Iteration) public void begin() {
            Arrays.fill(workers, null);
            before = fixture.database.redisReadMetrics();
        }
        @TearDown(Level.Iteration) public void end() {
            long min = Long.MAX_VALUE, total = 0;
            // JMH's preTearDown latch publishes worker state before any state teardown, also with -si false.
            for (Identity worker : workers) {
                if (worker == null) throw new IllegalStateException("Missing JMH worker registration");
                min = Math.min(min, worker.completed); total += worker.completed;
                if (worker.virtual != virtual) throw new IllegalStateException("Wrong actual JMH worker type");
            }
            if (min < 1) throw new IllegalStateException("At least one JMH worker made no progress");
            var delta = RedisCoalescingFixtures.Delta.between(before, fixture.database.redisReadMetrics());
            delta.require(coalescing, total, coalescing && workers.length >= 64);
            System.out.println("T1_ITER pid=" + ProcessHandle.current().pid() + " coalescing=" + coalescing
                    + " mode=" + mode + " threads=" + workers.length + " virtual=" + virtual
                    + " ordinal=" + ordinal++ + " min=" + min + " total=" + total + delta.fields());
        }
        @TearDown(Level.Trial) public void close() { if (fixture != null) fixture.close(); }
    }

    @State(Scope.Thread)
    public static class Identity {
        long value, completed;
        boolean virtual;
        @Setup(Level.Iteration) public void begin(Shared shared, ThreadParams worker) {
            value = worker.getThreadIndex() % 1000;
            completed = 0;
            virtual = Thread.currentThread().isVirtual();
            shared.workers[worker.getThreadIndex()] = this;
        }
        long next() { value = value % 1000 + 1; return value; }
    }

    @Benchmark public void l1MissFindById(Shared shared, Identity identity, Blackhole sink) {
        sink.consume(shared.fixture.repository.findById(identity.next()).orElseThrow());
        identity.completed++;
    }
    @Benchmark public void l1MissFindByIdVirtual1000(Shared shared, Identity identity, Blackhole sink) {
        if (!Thread.currentThread().isVirtual()) throw new IllegalStateException("Custom virtual executor required");
        sink.consume(shared.fixture.repository.findById(identity.next()).orElseThrow());
        identity.completed++;
    }
}

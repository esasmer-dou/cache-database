package com.reactor.cachedb.benchmarks.redis;

import com.reactor.cachedb.core.query.AggregateSpec;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:ActiveProcessorCount=2"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class RedisRepositoryBenchmark {
    @State(Scope.Benchmark)
    public static class HitState {
        RedisBenchmarkFixture fixture;
        @Setup(Level.Trial) public void setup() throws Exception { fixture = new RedisBenchmarkFixture(100, false, true, false); }
        @TearDown(Level.Trial) public void close() { fixture.close(); }
    }

    @State(Scope.Benchmark)
    public static class MissState {
        RedisBenchmarkFixture fixture;
        @Setup(Level.Trial) public void setup() throws Exception { fixture = new RedisBenchmarkFixture(1000, false, false, false); }
        @TearDown(Level.Trial) public void close() { fixture.close(); }
    }

    @State(Scope.Thread)
    public static class Identity {
        long value;
        long next() { value = value % 1000 + 1; return value; }
    }

    @State(Scope.Thread)
    public static class MixedState {
        RedisBenchmarkFixture fixture;
        List<Long> ids;
        @Setup(Level.Trial) public void setup() throws Exception {
            fixture = new RedisBenchmarkFixture(100, false, true, false);
            ids = LongStream.rangeClosed(1, 100).boxed().toList();
        }
        @Setup(Level.Invocation) public void resetMisses() {
            // A deterministic 50-hit/50-miss batch, with no Redis I/O in the reset.
            for (long id = 51; id <= 100; id++) fixture.store.invalidate(Long.toString(id));
        }
        @TearDown(Level.Trial) public void close() { fixture.close(); }
    }

    @State(Scope.Benchmark)
    public static class AggregateState {
        @Param({"false", "true"}) public boolean tenant;
        RedisBenchmarkFixture fixture;
        @Setup(Level.Trial) public void setup() throws Exception {
            fixture = new RedisBenchmarkFixture(10000, tenant, false, false);
            try (var scope = tenant ? CacheTenantContext.withTenant("tenant-a") : null) {
                if (fixture.repository.aggregate(AggregateSpec.count(null)).count() != 10000)
                    throw new IllegalStateException("Aggregate fixture does not contain exactly 10000 rows");
                var groups = fixture.repository.aggregateBy("group", AggregateSpec.count(null));
                if (groups.size() != 20 || groups.values().stream().mapToLong(value -> value.count()).sum() != 10000)
                    throw new IllegalStateException("Facet fixture is incomplete");
            }
        }
        @TearDown(Level.Trial) public void close() { fixture.close(); }
    }

    @State(Scope.Thread)
    public static class WriteState {
        RedisBenchmarkFixture fixture;
        long value;
        @Setup(Level.Trial) public void setup() throws Exception { fixture = new RedisBenchmarkFixture(1, false, false, true); }
        @TearDown(Level.Trial) public void close() { fixture.close(); }
    }

    @Benchmark public void l1HitFindById(HitState state, Blackhole sink) {
        sink.consume(state.fixture.repository.findById(1L).orElseThrow());
    }

    @Benchmark public void l1MissFindById(MissState state, Identity identity, Blackhole sink) {
        sink.consume(state.fixture.repository.findById(identity.next()).orElseThrow());
    }

    @Benchmark public void l1MissFindByIdVirtual1000(MissState state, Identity identity, Blackhole sink) {
        if (!Thread.currentThread().isVirtual()) throw new IllegalStateException("Virtual benchmark requires the custom executor");
        sink.consume(state.fixture.repository.findById(identity.next()).orElseThrow());
    }

    @Benchmark public void findAllMixed100(MixedState state, Blackhole sink) {
        var rows = state.fixture.repository.findAll(state.ids);
        if (rows.size() != 100) throw new IllegalStateException("Mixed lookup lost rows");
        sink.consume(rows);
    }

    @Benchmark public void aggregateCount10k(AggregateState state, Blackhole sink) {
        try (var scope = state.tenant ? CacheTenantContext.withTenant("tenant-a") : null) {
            sink.consume(state.fixture.repository.aggregate(AggregateSpec.count(null)));
        }
    }

    @Benchmark public void facet10k20Groups(AggregateState state, Blackhole sink) {
        try (var scope = state.tenant ? CacheTenantContext.withTenant("tenant-a") : null) {
            sink.consume(state.fixture.repository.aggregateBy("group", AggregateSpec.count(null)));
        }
    }

    @Benchmark public void saveSingle(WriteState state, Blackhole sink) {
        sink.consume(state.fixture.repository.save(new RedisBenchmarkFixture.Row(1L, "tenant-a", "group-1", ++state.value)));
    }
}

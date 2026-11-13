package com.reactor.cachedb.benchmarks.redis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ResultRole;
import org.openjdk.jmh.results.SampleTimeResult;

/** Opt-in JMH 1.37 result compaction; register before gc so its afterIteration runs after GC capture. */
@SuppressWarnings("rawtypes")
public final class JmhSampleCompactionProfiler implements InternalProfiler {
    @Override public String getDescription() {
        return "Join 1000-worker SampleTime histograms after timing without dropping sample bins";
    }

    @Override public void beforeIteration(BenchmarkParams benchmark, IterationParams iteration) { }

    @Override public Collection<? extends Result> afterIteration(
            BenchmarkParams benchmark, IterationParams iteration, IterationResult result) {
        if (benchmark.getMode() != Mode.SampleTime || benchmark.getThreads() != 1000) return List.of();

        Collection<Result> raw = result.getRawPrimaryResults();
        // JMH 1.37 uses an ArrayList for multiple primary results; do not mutate another protocol shape.
        if (!"1.37".equals(benchmark.getJmhVersion()) || raw.getClass() != ArrayList.class || raw.size() != 1000)
            throw new IllegalStateException("Expected JMH 1.37 mutable 1000-worker SampleTime primary results");
        for (Result primary : raw) {
            if (primary.getClass() != SampleTimeResult.class || primary.getRole() != ResultRole.PRIMARY)
                throw new IllegalStateException("Expected only primary SampleTimeResult worker histograms");
        }

        // JMH's own JoiningAggregator preserves the existing quantized bins and their multiplicities.
        Result joined = result.getPrimaryResult();
        if (joined.getClass() != SampleTimeResult.class)
            throw new IllegalStateException("JMH did not return the expected joined SampleTimeResult");
        raw.clear();
        raw.add(joined);
        return List.of();
    }
}

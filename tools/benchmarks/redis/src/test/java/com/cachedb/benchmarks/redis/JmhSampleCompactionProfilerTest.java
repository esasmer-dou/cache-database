package com.reactor.cachedb.benchmarks.redis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.infra.ThreadParams;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.ProfilerException;
import org.openjdk.jmh.results.*;
import org.openjdk.jmh.results.format.ResultFormatFactory;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.BenchmarkException;
import org.openjdk.jmh.runner.IterationType;
import org.openjdk.jmh.runner.InfraControl;
import org.openjdk.jmh.runner.WorkloadParams;
import org.openjdk.jmh.runner.format.OutputFormat;
import org.openjdk.jmh.runner.format.OutputFormatFactory;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.runner.options.VerboseMode;
import org.openjdk.jmh.util.SampleBuffer;
import org.openjdk.jmh.util.Statistics;

/** Dependency-free main test; execute explicitly in the parent-owned K8s validation cohort. */
@SuppressWarnings("rawtypes")
public final class JmhSampleCompactionProfilerTest {
    private static final JmhSampleCompactionProfiler PROFILER = new JmhSampleCompactionProfiler();

    public static void main(String[] args) throws Exception {
        histogramGcAndMetadataStayExact();
        otherModesAndThreadCountsStayUntouched();
        unexpectedPrimaryShapesFailBeforeMutation();
        wrongJmhVersionFailsBeforeMutation();
        realJmhDispatchCapturesGcBeforeCompaction();
        System.out.println("JmhSampleCompactionProfilerTest: 5 checks passed");
    }

    static void histogramGcAndMetadataStayExact() throws Exception {
        for (TimeUnit unit : List.of(TimeUnit.NANOSECONDS, TimeUnit.MICROSECONDS)) {
            for (IterationType type : IterationType.values()) {
                IterationResult result = fixture(Mode.SampleTime, 1000, 1000, unit, type);
                var benchmark = result.getBenchmarkParams();
                var iteration = result.getParams();
                var metadata = result.getMetadata();
                var raw = result.getRawPrimaryResults();
                var secondary = result.getRawSecondaryResults();
                var gc = new ArrayList<>(secondary.values());
                Result expected = result.getPrimaryResult();
                require(expected.getStatistics().getN() == 8000, "Fixture lost samples");
                String json = json(result);
                require(json.contains("\"rawDataHistogram\"") && json.contains("\"gc.alloc.rate.norm\""),
                        "JMH raw histogram or GC export disabled");

                PROFILER.beforeIteration(benchmark, iteration);
                require(PROFILER.afterIteration(benchmark, iteration, result).isEmpty(), "Added profiler metrics");
                require(result.getRawPrimaryResults() == raw && raw.size() == 1, "Did not compact in place");
                sameResult(expected, result.getPrimaryResult());
                require(result.getRawSecondaryResults() == secondary
                        && gc.equals(new ArrayList<>(secondary.values())), "Changed raw GC results");
                require(result.getBenchmarkParams() == benchmark && result.getParams() == iteration
                        && result.getMetadata() == metadata, "Changed metadata identity");
                require(metadata.getAllOps() == 12000 && metadata.getMeasuredOps() == 10000,
                        "Changed operation counts");
                require(json.equals(json(result)), "Changed JMH JSON histogram, statistics or GC secondary metrics");

                var bytes = new ByteArrayOutputStream();
                try (var output = new ObjectOutputStream(bytes)) { output.writeObject(result); }
                try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                    var restored = (IterationResult) input.readObject();
                    require(restored.getRawPrimaryResults().size() == 1, "Serialized worker partitions retained");
                    sameResult(expected, restored.getPrimaryResult());
                    require(json.equals(json(restored)), "Changed results across controller serialization");
                }
            }
        }
    }

    static void otherModesAndThreadCountsStayUntouched() {
        for (Mode mode : Mode.values()) {
            if (mode != Mode.SampleTime) unchanged(fixture(mode, 1000, 2,
                    TimeUnit.MICROSECONDS, IterationType.MEASUREMENT));
        }
        for (int threads : new int[] {1, 16, 64, 999, 1001})
            unchanged(fixture(Mode.SampleTime, threads, 2, TimeUnit.MICROSECONDS, IterationType.MEASUREMENT));
    }

    static void unexpectedPrimaryShapesFailBeforeMutation() {
        for (int count : new int[] {0, 1, 999, 1001})
            rejected(fixture(Mode.SampleTime, 1000, count, TimeUnit.MICROSECONDS, IterationType.MEASUREMENT));

        var mixed = fixture(Mode.SampleTime, 1000, 999, TimeUnit.MICROSECONDS, IterationType.MEASUREMENT);
        mixed.addResult(new ThroughputResult(ResultRole.PRIMARY, "sample", 1, 1, TimeUnit.MICROSECONDS));
        rejected(mixed);

        var source = fixture(Mode.SampleTime, 1000, 1000, TimeUnit.MICROSECONDS, IterationType.MEASUREMENT);
        var immutable = new IterationResult(source.getBenchmarkParams(), source.getParams(), source.getMetadata()) {
            @Override public Collection<Result> getRawPrimaryResults() {
                return Collections.unmodifiableCollection(super.getRawPrimaryResults());
            }
        };
        immutable.addResults(source.getRawPrimaryResults());
        rejected(immutable);

        var mismatched = fixture(Mode.SampleTime, 1000, 999, TimeUnit.MICROSECONDS, IterationType.MEASUREMENT);
        mismatched.addResult(new SampleTimeResult(ResultRole.PRIMARY, "other", new SampleBuffer(),
                TimeUnit.MICROSECONDS));
        rejected(mismatched);
    }

    private static IterationResult fixture(Mode mode, int threads, int count, TimeUnit unit, IterationType type) {
        return fixture(mode, threads, count, unit, type, "1.37");
    }

    private static IterationResult fixture(Mode mode, int threads, int count, TimeUnit unit,
                                           IterationType type, String jmhVersion) {
        var warmup = new IterationParams(IterationType.WARMUP, 5, TimeValue.seconds(3), 1);
        var measurement = new IterationParams(IterationType.MEASUREMENT, 5, TimeValue.seconds(3), 1);
        var benchmark = new BenchmarkParams("sample", JmhSampleCompactionProfilerTest.class.getName() + ".neverRun",
                false, threads, new int[] {threads}, List.of(),
                3, 0, warmup, measurement, mode, new WorkloadParams(), unit, 1,
                "java", List.of("-Xmx512m"), "21", "test", "21", jmhVersion, TimeValue.seconds(60));
        var result = new IterationResult(benchmark, type == IterationType.WARMUP ? warmup : measurement,
                new IterationResultMetaData(12000, 10000));
        for (int worker = 0; worker < count; worker++) {
            var buffer = new SampleBuffer();
            for (long value : new long[] {1, 1, 15, 1023, 1024, 1025, 1000000, 1000000000})
                buffer.add(value + worker % 3);
            result.addResult(new SampleTimeResult(ResultRole.PRIMARY, "sample", buffer, unit));
        }
        result.addResult(new ScalarResult("gc.alloc.rate", 12.5, "MB/sec", AggregationPolicy.AVG));
        result.addResult(new ScalarResult("gc.alloc.rate.norm", 4096, "B/op", AggregationPolicy.AVG));
        result.addResult(new ScalarResult("gc.count", 2, "counts", AggregationPolicy.SUM));
        result.addResult(new ScalarResult("gc.time", 17, "ms", AggregationPolicy.SUM));
        return result;
    }

    static void wrongJmhVersionFailsBeforeMutation() {
        for (String version : List.of("1.36", "1.38", "1.37-SNAPSHOT"))
            rejected(fixture(Mode.SampleTime, 1000, 1000, TimeUnit.MICROSECONDS,
                    IterationType.MEASUREMENT, version));
    }

    // Exercise JMH's actual reverse dispatch without starting workers or timing benchmark work.
    static void realJmhDispatchCapturesGcBeforeCompaction() throws Exception {
        for (boolean correctOrder : new boolean[] {true, false}) {
            var result = fixture(Mode.SampleTime, 1000, 1000, TimeUnit.MICROSECONDS,
                    IterationType.MEASUREMENT);
            var options = new OptionsBuilder();
            if (correctOrder) options.addProfiler(JmhSampleCompactionProfiler.class).addProfiler(GcOrderControl.class.getName());
            else options.addProfiler(GcOrderControl.class.getName()).addProfiler(JmhSampleCompactionProfiler.class);
            var handlerClass = Class.forName("org.openjdk.jmh.runner.BenchmarkHandler");
            var constructor = handlerClass.getDeclaredConstructor(OutputFormat.class, Options.class, BenchmarkParams.class);
            constructor.setAccessible(true);
            var bytes = new ByteArrayOutputStream();
            try (var output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
                Object handler = constructor.newInstance(OutputFormatFactory.createFormatInstance(output, VerboseMode.SILENT),
                        options.build(), result.getBenchmarkParams());
                try {
                    GcOrderControl.captured = List.of();
                    var start = handlerClass.getDeclaredMethod("startProfilers", BenchmarkParams.class, IterationParams.class);
                    var stop = handlerClass.getDeclaredMethod("stopProfilers", BenchmarkParams.class, IterationParams.class,
                            IterationResult.class);
                    start.setAccessible(true);
                    stop.setAccessible(true);
                    start.invoke(handler, result.getBenchmarkParams(), result.getParams());
                    try {
                        stop.invoke(handler, result.getBenchmarkParams(), result.getParams(), result);
                        require(correctOrder, "Accepted reversed profiler registration");
                        require(result.getRawPrimaryResults().size() == 1, "Real JMH dispatch did not compact");
                        require(!GcOrderControl.captured.isEmpty(), "Real GC profiler returned no results");
                        require(result.getRawSecondaryResults().values().containsAll(GcOrderControl.captured),
                                "Compaction discarded GC capture results");
                    } catch (InvocationTargetException failure) {
                        Throwable cause = failure.getCause();
                        if (correctOrder || cause == null || cause.getClass() != BenchmarkException.class
                                || cause.getCause() != null || cause.getSuppressed().length != 1
                                || cause.getSuppressed()[0].getClass() != IllegalStateException.class
                                || !"GC_CAPTURE_AFTER_COMPACTION".equals(cause.getSuppressed()[0].getMessage())
                                || cause.getSuppressed()[0].getCause() != null
                                || cause.getSuppressed()[0].getSuppressed().length != 0)
                            throw new AssertionError("Unexpected real profiler dispatch failure; correctOrder="
                                    + correctOrder, failure);
                    }
                } finally {
                    var shutdown = handlerClass.getDeclaredMethod("shutdown");
                    shutdown.setAccessible(true);
                    shutdown.invoke(handler);
                }
            }
        }
    }

    public BenchmarkTaskResult neverRun(InfraControl control, ThreadParams params) {
        throw new AssertionError("Ordering control must not run benchmark work");
    }

    public static final class GcOrderControl extends GCProfiler {
        static Collection<? extends Result> captured = List.of();

        public GcOrderControl(String options) throws ProfilerException { super(options); }

        @Override public Collection<? extends Result> afterIteration(
                BenchmarkParams benchmark, IterationParams iteration, IterationResult result) {
            if (result.getRawPrimaryResults().size() != 1000)
                throw new IllegalStateException("GC_CAPTURE_AFTER_COMPACTION");
            captured = super.afterIteration(benchmark, iteration, result);
            return captured;
        }
    }

    private static void unchanged(IterationResult result) {
        var raw = result.getRawPrimaryResults();
        var before = new ArrayList<>(raw);
        require(PROFILER.afterIteration(result.getBenchmarkParams(), result.getParams(), result).isEmpty(),
                "Unexpected profiler metric");
        require(result.getRawPrimaryResults() == raw && before.equals(new ArrayList<>(raw)),
                "Changed an unsupported mode or thread count");
    }

    private static void rejected(IterationResult result) {
        var raw = result.getRawPrimaryResults();
        var before = new ArrayList<>(raw);
        try {
            PROFILER.afterIteration(result.getBenchmarkParams(), result.getParams(), result);
            throw new AssertionError("Accepted unexpected primary result shape");
        } catch (IllegalStateException expected) {
            require(before.equals(new ArrayList<>(result.getRawPrimaryResults())), "Mutated before rejection");
        }
    }

    private static void sameResult(Result expected, Result actual) {
        require(expected.getRole() == actual.getRole() && expected.getLabel().equals(actual.getLabel())
                && expected.getScoreUnit().equals(actual.getScoreUnit()), "Changed result role, label or unit");
        Statistics a = expected.getStatistics(), b = actual.getStatistics();
        require(a.getN() == b.getN() && bins(a).equals(bins(b)), "Changed raw histogram counts");
        double[] left = {a.getMin(), a.getMax(), a.getMean(), a.getSum(), a.getVariance(), a.getStandardDeviation()};
        double[] right = {b.getMin(), b.getMax(), b.getMean(), b.getSum(), b.getVariance(), b.getStandardDeviation()};
        for (int i = 0; i < left.length; i++) equal(left[i], right[i]);
        for (double percentile : new double[] {0, 50, 90, 95, 99, 99.9, 99.99, 100})
            equal(a.getPercentile(percentile), b.getPercentile(percentile));
    }

    private static Map<Double, Long> bins(Statistics statistics) {
        var counts = new TreeMap<Double, Long>();
        statistics.getRawData().forEachRemaining(entry -> counts.merge(entry.getKey(), entry.getValue(), Long::sum));
        return counts;
    }

    private static String json(IterationResult result) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            var benchmark = result.getBenchmarkParams();
            ResultFormatFactory.getInstance(ResultFormatType.JSON, output).writeOut(List.of(
                    new RunResult(benchmark, List.of(new BenchmarkResult(benchmark, List.of(result))))));
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void equal(double expected, double actual) {
        require(Double.doubleToLongBits(expected) == Double.doubleToLongBits(actual), "Changed statistic");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

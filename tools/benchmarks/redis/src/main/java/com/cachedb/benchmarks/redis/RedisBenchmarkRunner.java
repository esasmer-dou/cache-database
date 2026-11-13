package com.reactor.cachedb.benchmarks.redis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatFactory;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/** Fixed acceptance settings; no short-run option that could be mistaken for release evidence. */
public final class RedisBenchmarkRunner {
    private RedisBenchmarkRunner() { }
    public static void main(String[] args) throws Exception {
        String endpoint = System.getProperty("cachedb.bench.redis");
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("Missing cachedb.bench.redis");
        String selected = System.getProperty("cachedb.bench.case", "all");
        var results = new ArrayList<RunResult>();
        run(results, selected, "l1HitFindById", 1, false, endpoint);
        for (int threads : new int[] {1, 16, 64}) run(results, selected, "l1MissFindById", threads, false, endpoint);
        run(results, selected, "l1MissFindByIdVirtual1000", 1000, true, endpoint);
        for (String name : new String[] {"findAllMixed100", "aggregateCount10k", "facet10k20Groups", "saveSingle"})
            run(results, selected, name, 1, false, endpoint);
        if (results.isEmpty()) throw new IllegalArgumentException("Unknown benchmark case: " + selected);
        Path output = Path.of(System.getProperty("cachedb.bench.output", "target/jmh-result.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        ResultFormatFactory.getInstance(ResultFormatType.JSON, output.toString()).writeOut(results);
    }
    private static void run(ArrayList<RunResult> results, String selected, String name, int threads,
            boolean virtual, String endpoint) throws Exception {
        if (!selected.equals("all") && !selected.equals(name)) return;
        var arguments = new ArrayList<String>();
        arguments.addAll(java.util.List.of("-Xms512m", "-Xmx512m", "-XX:ActiveProcessorCount=2",
                "-Dcachedb.bench.redis=" + endpoint));
        if (virtual) arguments.addAll(java.util.List.of("-Djmh.executor=CUSTOM",
                "-Djmh.executor.class=" + VirtualBenchmarkExecutor.class.getName()));
        var options = new OptionsBuilder().include(RedisRepositoryBenchmark.class.getName() + "\\." + name + "$")
                .threads(threads).forks(3).warmupIterations(5).measurementIterations(5)
                .shouldFailOnError(true)
                .jvmArgsAppend(arguments.toArray(String[]::new));
        if (virtual) options.addProfiler(JmhSampleCompactionProfiler.class);
        options.addProfiler("gc");
        // JMH 1.37 CUSTOM executors do not yield in iteration rendezvous spins.
        // Disable that optional spin rendezvous for oversubscribed virtual workers;
        // pre-setup/teardown barriers and the five timed iterations remain intact.
        if (virtual) options.syncIterations(false);
        results.addAll(new Runner(options.build()).run());
    }
}

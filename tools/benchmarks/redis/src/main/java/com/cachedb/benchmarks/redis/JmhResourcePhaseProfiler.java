package com.reactor.cachedb.benchmarks.redis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.runner.IterationType;

/** Linux-only iteration-envelope markers; register after compaction and before gc. */
@SuppressWarnings("rawtypes")
public final class JmhResourcePhaseProfiler implements InternalProfiler {
    private final Path directory;
    private Path events;
    private Path acknowledgement;
    private long pid;
    private String startTicks;
    private int sequence;
    private int warmup;
    private int measurement;
    private boolean active;

    public JmhResourcePhaseProfiler() {
        String value = System.getenv("CACHEDB_BENCH_RESOURCE_EVENTS");
        if (value == null || value.isBlank())
            throw new IllegalStateException("Resource sampler required: CACHEDB_BENCH_RESOURCE_EVENTS unavailable");
        directory = Path.of(value);
        if (!directory.isAbsolute() || !Files.isDirectory(directory) || Files.isSymbolicLink(directory))
            throw new IllegalStateException("Resource event directory must be an existing absolute directory");
    }

    @Override public String getDescription() {
        return "External Linux RSS/cgroup sampler phase markers; no JMH memory or allocation results";
    }

    @Override public void beforeIteration(BenchmarkParams benchmark, IterationParams iteration) {
        if (!"1.37".equals(benchmark.getJmhVersion()) || benchmark.getForks() < 1 || active)
            throw new IllegalStateException("Resource markers require JMH 1.37, real forks and balanced callbacks");
        if (iteration.getType() == IterationType.WARMUP) warmup++;
        else if (iteration.getType() == IterationType.MEASUREMENT) measurement++;
        else throw new IllegalStateException("Unsupported iteration phase");
        emit("START", benchmark, iteration);
        active = true;
    }

    @Override public Collection<? extends Result> afterIteration(
            BenchmarkParams benchmark, IterationParams iteration, IterationResult result) {
        if (!active) throw new IllegalStateException("Unbalanced resource phase end");
        emit("END", benchmark, iteration);
        active = false;
        return List.of();
    }

    static String startTicks(String stat) {
        int end = stat.lastIndexOf(')');
        if (end < 0) throw new IllegalStateException("Malformed /proc PID stat");
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        if (fields.length < 20 || !fields[19].matches("[0-9]+"))
            throw new IllegalStateException("Missing /proc PID starttime");
        return fields[19];
    }

    private static String field(String value) {
        if (value.length() > 1024 || value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalStateException("Unsafe resource marker field");
        return value;
    }

    private void emit(String kind, BenchmarkParams benchmark, IterationParams iteration) {
        try {
            if (events == null) {
                pid = ProcessHandle.current().pid();
                startTicks = startTicks(Files.readString(Path.of("/proc", Long.toString(pid), "stat")));
                String identity = pid + "-" + startTicks;
                events = directory.resolve(identity + ".events");
                acknowledgement = directory.resolve(identity + ".ack");
                Files.createFile(events);
            }
            if (++sequence > 512) throw new IllegalStateException("Resource marker event limit exceeded");
            String phase = iteration.getType().name();
            int index = iteration.getType() == IterationType.WARMUP ? warmup : measurement;
            StringBuilder parameters = new StringBuilder();
            for (String key : new TreeSet<>(benchmark.getParamsKeys())) {
                if (!parameters.isEmpty()) parameters.append(',');
                parameters.append(key).append('=').append(benchmark.getParam(key));
            }
            String row = sequence + "\t" + kind + "\t" + pid + "\t" + startTicks + "\t" + phase
                    + "\t" + index + "\t" + field(benchmark.getBenchmark()) + "\t" + field(benchmark.getMode().name())
                    + "\t" + field(parameters.toString()) + "\t" + benchmark.getWarmup().getCount()
                    + "\t" + benchmark.getMeasurement().getCount() + "\n";
            Files.writeString(events, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            // Acknowledgement keeps both boundary snapshots outside benchmark timing and GC allocation capture.
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(acknowledgement)
                        && Files.readString(acknowledgement).trim().equals(Integer.toString(sequence))) return;
                Thread.sleep(10);
            }
            throw new IllegalStateException("Resource sampler acknowledgement unavailable after 10 seconds");
        } catch (IOException e) {
            throw new IllegalStateException("Resource marker I/O unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Resource marker interrupted", e);
        }
    }
}

package com.reactor.cachedb.benchmarks.redis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;
import org.openjdk.jmh.runner.WorkloadParams;
import org.openjdk.jmh.runner.options.TimeValue;

/** Deterministic contract main; no workers, workload, Redis, GC or benchmark timing. */
public final class JmhResourcePhaseProfilerTest {
    public static void main(String[] args) throws Exception {
        String fields = "S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654 20";
        require(JmhResourcePhaseProfiler.startTicks("123 (java) " + fields).equals("987654"));
        require(JmhResourcePhaseProfiler.startTicks("123 (a name ) with parentheses) " + fields).equals("987654"));
        rejected("invalid");
        rejected("123 (java) S 1");
        rejected("123 (java) " + fields.replace("987654", "invalid"));
        callbackMarkersAndAcknowledgements();
        System.out.println("JmhResourcePhaseProfilerTest: 6 checks passed");
    }

    private static void callbackMarkersAndAcknowledgements() throws Exception {
        Path directory = Path.of(System.getenv("CACHEDB_BENCH_RESOURCE_EVENTS"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread acknowledger = Thread.ofPlatform().daemon().start(() -> {
            long deadline = System.nanoTime() + 8_000_000_000L;
            try {
                while (System.nanoTime() < deadline) {
                    try (var files = Files.newDirectoryStream(directory, "*.events")) {
                        for (Path file : files) {
                            List<String> rows = Files.readAllLines(file);
                            if (rows.isEmpty()) continue;
                            String[] row = rows.getLast().split("\t", -1);
                            if (row.length != 11) continue; // A partial append is not an event.
                            Path temporary = directory.resolve("test-ack.tmp");
                            Files.writeString(temporary, row[0] + "\n");
                            Files.move(temporary, directory.resolve(file.getFileName().toString().replace(".events", ".ack")),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            if (row[0].equals("4")) return;
                        }
                    }
                    Thread.sleep(5);
                }
                throw new AssertionError("Phase acknowledgement contract deadline");
            } catch (Throwable problem) { failure.set(problem); }
        });
        var warmup = new IterationParams(IterationType.WARMUP, 1, TimeValue.seconds(1), 1);
        var measurement = new IterationParams(IterationType.MEASUREMENT, 1, TimeValue.seconds(1), 1);
        var benchmark = new BenchmarkParams("contract", "contract.neverRun", false, 1, new int[] {1}, List.of(),
                1, 0, warmup, measurement, Mode.Throughput, new WorkloadParams(), TimeUnit.SECONDS, 1,
                "java", List.of(), "21", "contract", "21", "1.37", TimeValue.seconds(1));
        var profiler = new JmhResourcePhaseProfiler();
        profiler.beforeIteration(benchmark, warmup);
        require(profiler.afterIteration(benchmark, warmup, null).isEmpty());
        profiler.beforeIteration(benchmark, measurement);
        require(profiler.afterIteration(benchmark, measurement, null).isEmpty());
        acknowledger.join(9000);
        require(!acknowledger.isAlive() && failure.get() == null);
        try (var files = Files.newDirectoryStream(directory, "*.events")) {
            for (Path file : files) {
                List<String> rows = Files.readAllLines(file);
                require(rows.size() == 4);
                for (int index = 0; index < rows.size(); index++) {
                    String[] row = rows.get(index).split("\t", -1);
                    require(row.length == 11 && row[0].equals(Integer.toString(index + 1)));
                    require(row[1].equals(index % 2 == 0 ? "START" : "END"));
                    require(row[4].equals(index < 2 ? "WARMUP" : "MEASUREMENT"));
                    require(row[5].equals("1") && row[9].equals("1") && row[10].equals("1"));
                }
            }
        }
    }

    private static void rejected(String stat) {
        try {
            JmhResourcePhaseProfiler.startTicks(stat);
            throw new AssertionError("Accepted malformed PID identity");
        } catch (IllegalStateException expected) { }
    }

    private static void require(boolean value) {
        if (!value) throw new AssertionError("Incorrect /proc PID starttime");
    }
}

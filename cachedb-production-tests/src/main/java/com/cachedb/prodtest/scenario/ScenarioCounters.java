package com.reactor.cachedb.prodtest.scenario;

import java.util.function.LongSupplier;

/** Worker-local accounting; merge once after a worker stops, never on the measured hot path. */
final class ScenarioCounters {
    private final LongSupplier nanoClock;
    private final long startedAtNanos;
    private long finishedAtNanos;
    private boolean finished;
    long executedOperations;
    long readOperations;
    long writeOperations;
    long failedOperations;
    final Latencies all = new Latencies();
    final Latencies successful = new Latencies();
    final Latencies failed = new Latencies();
    final Latencies scheduledStartLag = new Latencies();

    ScenarioCounters() {
        this(System::nanoTime);
    }

    ScenarioCounters(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.startedAtNanos = nanoClock.getAsLong();
    }

    void record(boolean success, long latencyMicros, long startLagMicros) {
        executedOperations++;
        all.record(latencyMicros);
        scheduledStartLag.record(startLagMicros);
        if (success) {
            successful.record(latencyMicros);
        } else {
            failedOperations++;
            failed.record(latencyMicros);
        }
    }

    synchronized void merge(ScenarioCounters worker) {
        executedOperations += worker.executedOperations;
        readOperations += worker.readOperations;
        writeOperations += worker.writeOperations;
        failedOperations += worker.failedOperations;
        all.merge(worker.all);
        successful.merge(worker.successful);
        failed.merge(worker.failed);
        scheduledStartLag.merge(worker.scheduledStartLag);
    }

    void finish() {
        finishedAtNanos = nanoClock.getAsLong();
        finished = true;
    }

    long elapsedMillis() {
        return Math.max(1L, elapsedNanos() / 1_000_000L);
    }

    private long elapsedNanos() {
        return Math.max(1L, (finished ? finishedAtNanos : nanoClock.getAsLong()) - startedAtNanos);
    }

    long averageLatencyMicros() { return all.average(); }
    long p95LatencyMicros() { return all.percentileUpperBound(0.95d); }
    long p99LatencyMicros() { return all.percentileUpperBound(0.99d); }

    double achievedTransactionsPerSecond() {
        return (executedOperations - failedOperations) * 1_000_000_000.0d / elapsedNanos();
    }

    double attemptedOperationsPerSecond() {
        return executedOperations * 1_000_000_000.0d / elapsedNanos();
    }

    String measurementJson() {
        return "\"measurementVersion\":2,\"attemptedOperations\":" + executedOperations
                + ",\"successfulOperations\":" + (executedOperations - failedOperations)
                + ",\"attemptedOperationsPerSecond\":" + attemptedOperationsPerSecond()
                + ",\"latencyScope\":\"all-attempt execution; excludes pacing and post-load drain\""
                + ",\"loadModel\":\"bounded workers with scheduled pacing; not open-loop capacity\""
                + ",\"latencyHistogram\":" + all.json()
                + ",\"successfulLatency\":" + successful.json()
                + ",\"failedLatency\":" + failed.json()
                + ",\"scheduledStartLag\":" + scheduledStartLag.json() + ",";
    }

    String measurementMarkdown() {
        return "- measurementVersion: `2`\n"
                + "- successfulOperations: `" + (executedOperations - failedOperations) + "`\n"
                + "- attemptedOperationsPerSecond: `" + attemptedOperationsPerSecond() + "`\n"
                + "- throughput scope: successful operations; async writes are not SQL commits\n"
                + "- latency scope: all attempts, execution only; pacing and post-load drain excluded\n"
                + "- latency histogram: bucket upper bounds; overflow uses observed maximum, not a clipped percentile\n"
                + "- latencyHistogramOverflowCount: `" + all.overflow + "`\n"
                + "- p95UsesOverflowMaximum: `" + all.usesOverflowMaximum(0.95d) + "`\n"
                + "- p99UsesOverflowMaximum: `" + all.usesOverflowMaximum(0.99d) + "`\n"
                + "- successfulLatencyMicros (avg/p95/p99): `" + successful.average() + "/"
                + successful.percentileUpperBound(0.95d) + "/" + successful.percentileUpperBound(0.99d) + "`\n"
                + "- failedLatencyMicros (avg/p95/p99): `" + failed.average() + "/"
                + failed.percentileUpperBound(0.95d) + "/" + failed.percentileUpperBound(0.99d) + "`\n"
                + "- scheduledStartLagP99Micros: `" + scheduledStartLag.percentileUpperBound(0.99d) + "`\n"
                + "- load model: bounded paced workers, not an open-loop capacity certification\n";
    }

    static final class Latencies {
        private static final long[] BOUNDS = {50L, 100L, 250L, 500L, 1_000L, 2_500L, 5_000L,
                10_000L, 20_000L, 50_000L, 100_000L, 250_000L, 500_000L, 1_000_000L,
                2_000_000L, 5_000_000L};
        private final long[] buckets = new long[BOUNDS.length];
        long count;
        long total;
        long maximum;
        long overflow;

        void record(long micros) {
            if (micros < 0L) throw new IllegalArgumentException("Latency must be nonnegative");
            count++;
            total += micros;
            maximum = Math.max(maximum, micros);
            for (int i = 0; i < BOUNDS.length; i++) {
                if (micros <= BOUNDS[i]) {
                    buckets[i]++;
                    return;
                }
            }
            overflow++;
        }

        void merge(Latencies other) {
            count += other.count;
            total += other.total;
            maximum = Math.max(maximum, other.maximum);
            overflow += other.overflow;
            for (int i = 0; i < buckets.length; i++) buckets[i] += other.buckets[i];
        }

        long average() { return count == 0L ? 0L : total / count; }

        boolean usesOverflowMaximum(double percentile) {
            validatePercentile(percentile);
            return overflow > 0L && (long) Math.ceil(count * percentile) > count - overflow;
        }

        long percentileUpperBound(double percentile) {
            validatePercentile(percentile);
            if (count == 0L) return 0L;
            long target = Math.max(1L, (long) Math.ceil(count * percentile));
            long seen = 0L;
            for (int i = 0; i < buckets.length; i++) {
                seen += buckets[i];
                if (seen >= target) return BOUNDS[i];
            }
            return maximum;
        }

        private static void validatePercentile(double percentile) {
            if (!Double.isFinite(percentile) || percentile <= 0.0d || percentile > 1.0d)
                throw new IllegalArgumentException("Percentile must be in (0, 1]");
        }

        String json() {
            return "{\"samples\":" + count + ",\"averageMicros\":" + average()
                    + ",\"p95UpperBoundMicros\":" + percentileUpperBound(0.95d)
                    + ",\"p99UpperBoundMicros\":" + percentileUpperBound(0.99d)
                    + ",\"maximumMicros\":" + maximum + ",\"overflowCount\":" + overflow
                    + ",\"p95UsesOverflowMaximum\":" + usesOverflowMaximum(0.95d)
                    + ",\"p99UsesOverflowMaximum\":" + usesOverflowMaximum(0.99d) + "}";
        }
    }
}

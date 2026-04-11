package com.reactor.cachedb.prodtest.scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RankedProjectionBenchmarkRunner {

    private static final int DEFAULT_WARMUP_ITERATIONS =
            Integer.getInteger("cachedb.prod.rankedProjectionBenchmark.warmupIterations", 5_000);
    private static final int DEFAULT_MEASURED_ITERATIONS =
            Integer.getInteger("cachedb.prod.rankedProjectionBenchmark.measuredIterations", 20_000);
    private static final int DEFAULT_DATASET_SIZE =
            Integer.getInteger("cachedb.prod.rankedProjectionBenchmark.datasetSize", 4_096);
    private static final int DEFAULT_TOP_WINDOW =
            Integer.getInteger("cachedb.prod.rankedProjectionBenchmark.topWindow", 24);
    private static final int DEFAULT_MINIMUM_LINE_COUNT =
            Integer.getInteger("cachedb.prod.rankedProjectionBenchmark.minimumLineCount", 18);
    private static final long TARGET_CUSTOMER_ID =
            Long.getLong("cachedb.prod.rankedProjectionBenchmark.targetCustomerId", 101L);
    private static final String DISCLAIMER =
            "This benchmark isolates ranked projection list retrieval inside the application layer. "
                    + "It compares a wide candidate scan against a ranked top-window projection path. "
                    + "It does not benchmark Redis network I/O.";

    private static volatile long benchmarkBlackhole;

    public RankedProjectionBenchmarkReport run() {
        return run(
                DEFAULT_WARMUP_ITERATIONS,
                DEFAULT_MEASURED_ITERATIONS,
                DEFAULT_DATASET_SIZE,
                DEFAULT_TOP_WINDOW,
                DEFAULT_MINIMUM_LINE_COUNT
        );
    }

    public RankedProjectionBenchmarkReport run(
            int warmupIterations,
            int measuredIterations,
            int datasetSize,
            int topWindowSize,
            int minimumLineCount
    ) {
        BenchmarkFixture fixture = new BenchmarkFixture(datasetSize, TARGET_CUSTOMER_ID, minimumLineCount);
        ArrayList<RankedProjectionBenchmarkReport.ShapeReport> shapeReports = new ArrayList<>();
        for (ShapeSpec shape : ShapeSpec.values()) {
            shapeReports.add(runShape(shape, fixture, warmupIterations, measuredIterations, topWindowSize));
        }
        String fastestAverageShape = shapeReports.stream()
                .min(Comparator.comparingLong(RankedProjectionBenchmarkReport.ShapeReport::averageLatencyNanos))
                .map(RankedProjectionBenchmarkReport.ShapeReport::shapeName)
                .orElse("");
        return new RankedProjectionBenchmarkReport(
                "ranked-projection-top-window-comparison",
                warmupIterations,
                measuredIterations,
                datasetSize,
                topWindowSize,
                DISCLAIMER,
                fastestAverageShape,
                List.copyOf(shapeReports)
        );
    }

    private RankedProjectionBenchmarkReport.ShapeReport runShape(
            ShapeSpec shape,
            BenchmarkFixture fixture,
            int warmupIterations,
            int measuredIterations,
            int topWindowSize
    ) {
        long[] samples = new long[measuredIterations];
        for (int iteration = 0; iteration < warmupIterations; iteration++) {
            consume(materialize(shape, fixture, topWindowSize));
        }
        for (int iteration = 0; iteration < measuredIterations; iteration++) {
            long startedAt = System.nanoTime();
            Object result = materialize(shape, fixture, topWindowSize);
            long elapsed = System.nanoTime() - startedAt;
            samples[iteration] = elapsed;
            consume(result);
        }
        return new RankedProjectionBenchmarkReport.ShapeReport(
                shape.name(),
                shape.label,
                shape.materializedObjectsPerOperation(fixture, topWindowSize),
                average(samples),
                percentile(samples, 0.95d),
                percentile(samples, 0.99d)
        );
    }

    private static Object materialize(ShapeSpec shape, BenchmarkFixture fixture, int topWindowSize) {
        return switch (shape) {
            case WIDE_CANDIDATE_SCAN -> {
                ArrayList<OrderRankedSummaryView> matches = new ArrayList<>();
                for (ProjectedOrderRow row : fixture.rows) {
                    if (row.customerId() != fixture.targetCustomerId || row.lineItemCount() < fixture.minimumLineCount) {
                        continue;
                    }
                    matches.add(new OrderRankedSummaryView(row.id(), row.customerId(), row.lineItemCount(), row.totalAmount(), row.rankScore()));
                }
                matches.sort(Comparator.comparingDouble(OrderRankedSummaryView::rankScore).reversed());
                int toIndex = Math.min(Math.max(0, topWindowSize), matches.size());
                yield List.copyOf(matches.subList(0, toIndex));
            }
            case RANKED_TOP_WINDOW -> {
                ArrayList<OrderRankedSummaryView> matches = new ArrayList<>(topWindowSize);
                for (ProjectedOrderRow row : fixture.rowsSortedByRank) {
                    if (row.customerId() != fixture.targetCustomerId || row.lineItemCount() < fixture.minimumLineCount) {
                        continue;
                    }
                    matches.add(new OrderRankedSummaryView(row.id(), row.customerId(), row.lineItemCount(), row.totalAmount(), row.rankScore()));
                    if (matches.size() >= topWindowSize) {
                        break;
                    }
                }
                yield List.copyOf(matches);
            }
        };
    }

    private static long average(long[] values) {
        if (values.length == 0) {
            return 0L;
        }
        long total = 0L;
        for (long value : values) {
            total += value;
        }
        return total / values.length;
    }

    private static long percentile(long[] values, double percentile) {
        if (values.length == 0) {
            return 0L;
        }
        long[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int index = (int) Math.ceil(percentile * copy.length) - 1;
        index = Math.max(0, Math.min(index, copy.length - 1));
        return copy[index];
    }

    private static void consume(Object result) {
        if (result instanceof List<?> list) {
            benchmarkBlackhole ^= list.size();
            if (!list.isEmpty()) {
                benchmarkBlackhole ^= list.get(0).hashCode();
            }
            return;
        }
        benchmarkBlackhole ^= result == null ? 11L : result.hashCode();
    }

    private enum ShapeSpec {
        WIDE_CANDIDATE_SCAN("Wide candidate scan") {
            @Override
            int materializedObjectsPerOperation(BenchmarkFixture fixture, int topWindowSize) {
                return fixture.estimatedCandidateCount;
            }
        },
        RANKED_TOP_WINDOW("Ranked projection top window") {
            @Override
            int materializedObjectsPerOperation(BenchmarkFixture fixture, int topWindowSize) {
                return Math.min(topWindowSize, fixture.estimatedCandidateCount);
            }
        };

        private final String label;

        ShapeSpec(String label) {
            this.label = label;
        }

        abstract int materializedObjectsPerOperation(BenchmarkFixture fixture, int topWindowSize);
    }

    private record BenchmarkFixture(
            List<ProjectedOrderRow> rows,
            List<ProjectedOrderRow> rowsSortedByRank,
            long targetCustomerId,
            int minimumLineCount,
            int estimatedCandidateCount
    ) {
        private BenchmarkFixture(int datasetSize, long targetCustomerId, int minimumLineCount) {
            this(
                    createRows(datasetSize, targetCustomerId),
                    createRows(datasetSize, targetCustomerId).stream()
                            .sorted(Comparator.comparingDouble(ProjectedOrderRow::rankScore).reversed())
                            .toList(),
                    targetCustomerId,
                    minimumLineCount,
                    estimateCandidateCount(createRows(datasetSize, targetCustomerId), targetCustomerId, minimumLineCount)
            );
        }

        private static List<ProjectedOrderRow> createRows(int datasetSize, long targetCustomerId) {
            ArrayList<ProjectedOrderRow> rows = new ArrayList<>(datasetSize);
            for (int index = 0; index < datasetSize; index++) {
                long id = index + 1L;
                long customerId = index % 9 == 0 ? targetCustomerId : 20_000L + (index % 256);
                int lineItemCount = 4 + (index % 32);
                double totalAmount = 40.0d + ((index % 120) * 3.75d);
                double rankScore = (lineItemCount * 1_000_000_000_000d) + Math.round(totalAmount * 100.0d);
                rows.add(new ProjectedOrderRow(id, customerId, lineItemCount, totalAmount, rankScore));
            }
            return List.copyOf(rows);
        }

        private static int estimateCandidateCount(List<ProjectedOrderRow> rows, long targetCustomerId, int minimumLineCount) {
            int count = 0;
            for (ProjectedOrderRow row : rows) {
                if (row.customerId() == targetCustomerId && row.lineItemCount() >= minimumLineCount) {
                    count++;
                }
            }
            return count;
        }
    }

    private record ProjectedOrderRow(
            long id,
            long customerId,
            int lineItemCount,
            double totalAmount,
            double rankScore
    ) {
    }

    private record OrderRankedSummaryView(
            long id,
            long customerId,
            int lineItemCount,
            double totalAmount,
            double rankScore
    ) {
    }
}

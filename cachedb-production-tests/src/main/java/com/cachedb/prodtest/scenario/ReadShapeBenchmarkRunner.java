package com.reactor.cachedb.prodtest.scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ReadShapeBenchmarkRunner {

    private static final int DEFAULT_WARMUP_ITERATIONS =
            Integer.getInteger("cachedb.prod.readShapeBenchmark.warmupIterations", 5_000);
    private static final int DEFAULT_MEASURED_ITERATIONS =
            Integer.getInteger("cachedb.prod.readShapeBenchmark.measuredIterations", 20_000);
    private static final int DEFAULT_ORDERS_PER_READ =
            Integer.getInteger("cachedb.prod.readShapeBenchmark.ordersPerRead", 24);
    private static final int DEFAULT_FULL_LINES_PER_ORDER =
            Integer.getInteger("cachedb.prod.readShapeBenchmark.fullLinesPerOrder", 48);
    private static final int DEFAULT_PREVIEW_LINES_PER_ORDER =
            Integer.getInteger("cachedb.prod.readShapeBenchmark.previewLinesPerOrder", 8);
    private static final String DISCLAIMER =
            "This benchmark isolates relation-heavy read-shape cost inside the application layer. "
                    + "It compares summary-only, summary-plus-single-detail, preview-list, and full-aggregate list patterns. "
                    + "It does not benchmark Redis network I/O or PostgreSQL I/O. "
                    + "It is meant to make object-graph and materialization tradeoffs visible.";

    private static volatile long benchmarkBlackhole;

    public ReadShapeBenchmarkReport run() {
        return run(
                DEFAULT_WARMUP_ITERATIONS,
                DEFAULT_MEASURED_ITERATIONS,
                DEFAULT_ORDERS_PER_READ,
                DEFAULT_FULL_LINES_PER_ORDER,
                DEFAULT_PREVIEW_LINES_PER_ORDER
        );
    }

    public ReadShapeBenchmarkReport run(
            int warmupIterations,
            int measuredIterations,
            int ordersPerRead,
            int fullLinesPerOrder,
            int previewLinesPerOrder
    ) {
        BenchmarkFixture fixture = new BenchmarkFixture(ordersPerRead, fullLinesPerOrder, previewLinesPerOrder);
        ArrayList<ReadShapeBenchmarkReport.ShapeReport> shapeReports = new ArrayList<>();
        for (ShapeSpec shape : ShapeSpec.values()) {
            shapeReports.add(runShape(shape, fixture, warmupIterations, measuredIterations));
        }
        long fastestAverage = shapeReports.stream()
                .mapToLong(ReadShapeBenchmarkReport.ShapeReport::averageLatencyNanos)
                .min()
                .orElse(0L);
        List<ReadShapeBenchmarkReport.ShapeReport> normalized = shapeReports.stream()
                .map(report -> new ReadShapeBenchmarkReport.ShapeReport(
                        report.shapeName(),
                        report.label(),
                        report.positioning(),
                        report.materializedOrdersPerOperation(),
                        report.materializedLineViewsPerOperation(),
                        report.estimatedObjectCountPerOperation(),
                        report.totalLatencyNanos(),
                        report.averageLatencyNanos(),
                        report.p95LatencyNanos(),
                        report.p99LatencyNanos(),
                        relativeOverheadPercent(report.averageLatencyNanos(), fastestAverage)
                ))
                .toList();
        String fastestAverageShape = normalized.stream()
                .min(Comparator.comparingLong(ReadShapeBenchmarkReport.ShapeReport::averageLatencyNanos))
                .map(ReadShapeBenchmarkReport.ShapeReport::shapeName)
                .orElse("");
        double maxAverageSpreadPercent = normalized.stream()
                .mapToDouble(report -> Math.abs(report.averageLatencyVsFastestPercent()))
                .max()
                .orElse(0.0d);
        return new ReadShapeBenchmarkReport(
                "relation-read-shape-comparison",
                warmupIterations,
                measuredIterations,
                ordersPerRead,
                fullLinesPerOrder,
                previewLinesPerOrder,
                DISCLAIMER,
                fastestAverageShape,
                maxAverageSpreadPercent,
                normalized
        );
    }

    private ReadShapeBenchmarkReport.ShapeReport runShape(
            ShapeSpec shape,
            BenchmarkFixture fixture,
            int warmupIterations,
            int measuredIterations
    ) {
        long[] samples = new long[measuredIterations];
        long totalLatencyNanos = 0L;
        for (int iteration = 0; iteration < warmupIterations; iteration++) {
            consume(materialize(shape, fixture));
        }
        for (int iteration = 0; iteration < measuredIterations; iteration++) {
            long startedAt = System.nanoTime();
            Object result = materialize(shape, fixture);
            long elapsed = System.nanoTime() - startedAt;
            samples[iteration] = elapsed;
            totalLatencyNanos += elapsed;
            consume(result);
        }
        return new ReadShapeBenchmarkReport.ShapeReport(
                shape.name(),
                shape.label,
                shape.positioning,
                shape.materializedOrders(fixture),
                shape.materializedLines(fixture),
                shape.estimatedObjectCount(fixture),
                totalLatencyNanos,
                measuredIterations == 0 ? 0L : totalLatencyNanos / measuredIterations,
                percentile(samples, 0.95d),
                percentile(samples, 0.99d),
                0.0d
        );
    }

    private static Object materialize(ShapeSpec shape, BenchmarkFixture fixture) {
        return switch (shape) {
            case SUMMARY_LIST -> {
                ArrayList<OrderSummaryView> summaries = new ArrayList<>(fixture.orders.size());
                for (RawOrder order : fixture.orders) {
                    summaries.add(new OrderSummaryView(
                            order.id(),
                            order.customerId(),
                            order.status(),
                            order.totalAmount(),
                            order.sku()
                    ));
                }
                yield List.copyOf(summaries);
            }
            case SUMMARY_PLUS_SINGLE_PREVIEW -> {
                ArrayList<OrderSummaryView> summaries = new ArrayList<>(fixture.orders.size());
                for (RawOrder order : fixture.orders) {
                    summaries.add(new OrderSummaryView(
                            order.id(),
                            order.customerId(),
                            order.status(),
                            order.totalAmount(),
                            order.sku()
                    ));
                }
                RawOrder selected = fixture.orders.get(0);
                yield new SummaryWithPreviewDetail(
                        List.copyOf(summaries),
                        new OrderPreviewView(
                                selected.id(),
                                selected.customerId(),
                                selected.status(),
                                selected.totalAmount(),
                                materializeLines(selected.lines(), fixture.previewLinesPerOrder)
                        )
                );
            }
            case PREVIEW_LIST -> {
                ArrayList<OrderPreviewView> previews = new ArrayList<>(fixture.orders.size());
                for (RawOrder order : fixture.orders) {
                    previews.add(new OrderPreviewView(
                            order.id(),
                            order.customerId(),
                            order.status(),
                            order.totalAmount(),
                            materializeLines(order.lines(), fixture.previewLinesPerOrder)
                    ));
                }
                yield List.copyOf(previews);
            }
            case FULL_AGGREGATE_LIST -> {
                ArrayList<OrderAggregateView> aggregates = new ArrayList<>(fixture.orders.size());
                for (RawOrder order : fixture.orders) {
                    aggregates.add(new OrderAggregateView(
                            order.id(),
                            order.customerId(),
                            order.sku(),
                            order.quantity(),
                            order.totalAmount(),
                            order.status(),
                            materializeLines(order.lines(), order.lines().size())
                    ));
                }
                yield List.copyOf(aggregates);
            }
        };
    }

    private static List<LineView> materializeLines(List<RawLine> rawLines, int limit) {
        int bounded = Math.max(0, Math.min(limit, rawLines.size()));
        ArrayList<LineView> lines = new ArrayList<>(bounded);
        for (int index = 0; index < bounded; index++) {
            RawLine raw = rawLines.get(index);
            lines.add(new LineView(raw.id(), raw.sku(), raw.quantity(), raw.unitPrice(), raw.status()));
        }
        return List.copyOf(lines);
    }

    private static void consume(Object result) {
        if (result == null) {
            benchmarkBlackhole ^= 3L;
            return;
        }
        if (result instanceof List<?> list) {
            benchmarkBlackhole ^= list.size();
            if (!list.isEmpty()) {
                benchmarkBlackhole ^= list.get(0).hashCode();
            }
            return;
        }
        benchmarkBlackhole ^= result.hashCode();
    }

    private static double relativeOverheadPercent(long value, long baseline) {
        if (baseline <= 0L) {
            return 0.0d;
        }
        return ((value - baseline) / (double) baseline) * 100.0d;
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

    private enum ShapeSpec {
        SUMMARY_LIST(
                "Summary list",
                "Best first-paint choice for list and dashboard screens."
        ) {
            @Override
            int materializedOrders(BenchmarkFixture fixture) {
                return fixture.ordersPerRead;
            }

            @Override
            int materializedLines(BenchmarkFixture fixture) {
                return 0;
            }
        },
        SUMMARY_PLUS_SINGLE_PREVIEW(
                "Summary list plus single preview detail",
                "Recommended summary/detail pattern when a user expands one row."
        ) {
            @Override
            int materializedOrders(BenchmarkFixture fixture) {
                return fixture.ordersPerRead + 1;
            }

            @Override
            int materializedLines(BenchmarkFixture fixture) {
                return fixture.previewLinesPerOrder;
            }
        },
        PREVIEW_LIST(
                "Preview list",
                "Use when every row needs a bounded child preview on first paint."
        ) {
            @Override
            int materializedOrders(BenchmarkFixture fixture) {
                return fixture.ordersPerRead;
            }

            @Override
            int materializedLines(BenchmarkFixture fixture) {
                return fixture.ordersPerRead * fixture.previewLinesPerOrder;
            }
        },
        FULL_AGGREGATE_LIST(
                "Full aggregate list",
                "Most expensive path; reserve for true detail screens, not first-paint lists."
        ) {
            @Override
            int materializedOrders(BenchmarkFixture fixture) {
                return fixture.ordersPerRead;
            }

            @Override
            int materializedLines(BenchmarkFixture fixture) {
                return fixture.ordersPerRead * fixture.fullLinesPerOrder;
            }
        };

        private final String label;
        private final String positioning;

        ShapeSpec(String label, String positioning) {
            this.label = label;
            this.positioning = positioning;
        }

        abstract int materializedOrders(BenchmarkFixture fixture);

        abstract int materializedLines(BenchmarkFixture fixture);

        int estimatedObjectCount(BenchmarkFixture fixture) {
            return materializedOrders(fixture) + materializedLines(fixture);
        }
    }

    private record BenchmarkFixture(
            int ordersPerRead,
            int fullLinesPerOrder,
            int previewLinesPerOrder,
            List<RawOrder> orders
    ) {
        private BenchmarkFixture(int ordersPerRead, int fullLinesPerOrder, int previewLinesPerOrder) {
            this(
                    ordersPerRead,
                    fullLinesPerOrder,
                    previewLinesPerOrder,
                    createOrders(ordersPerRead, fullLinesPerOrder)
            );
        }

        private static List<RawOrder> createOrders(int ordersPerRead, int fullLinesPerOrder) {
            ArrayList<RawOrder> orders = new ArrayList<>(ordersPerRead);
            for (int orderIndex = 0; orderIndex < ordersPerRead; orderIndex++) {
                long orderId = orderIndex + 1L;
                ArrayList<RawLine> lines = new ArrayList<>(fullLinesPerOrder);
                for (int lineIndex = 0; lineIndex < fullLinesPerOrder; lineIndex++) {
                    long lineId = (orderId * 10_000L) + lineIndex;
                    lines.add(new RawLine(
                            lineId,
                            "SKU-" + orderId + '-' + lineIndex,
                            (lineIndex % 4) + 1,
                            10.0d + (lineIndex * 1.15d),
                            lineIndex % 3 == 0 ? "ALLOCATED" : "READY"
                    ));
                }
                orders.add(new RawOrder(
                        orderId,
                        10_000L + orderIndex,
                        "SKU-" + orderId,
                        (orderIndex % 4) + 1,
                        120.0d + (orderIndex * 8.5d),
                        orderIndex % 2 == 0 ? "READY" : "PAID",
                        List.copyOf(lines)
                ));
            }
            return List.copyOf(orders);
        }
    }

    private record RawOrder(
            long id,
            long customerId,
            String sku,
            int quantity,
            double totalAmount,
            String status,
            List<RawLine> lines
    ) {
    }

    private record RawLine(
            long id,
            String sku,
            int quantity,
            double unitPrice,
            String status
    ) {
    }

    private record OrderSummaryView(
            long id,
            long customerId,
            String status,
            double totalAmount,
            String sku
    ) {
    }

    private record OrderPreviewView(
            long id,
            long customerId,
            String status,
            double totalAmount,
            List<LineView> lines
    ) {
    }

    private record OrderAggregateView(
            long id,
            long customerId,
            String sku,
            int quantity,
            double totalAmount,
            String status,
            List<LineView> lines
    ) {
    }

    private record LineView(
            long id,
            String sku,
            int quantity,
            double unitPrice,
            String status
    ) {
    }

    private record SummaryWithPreviewDetail(
            List<OrderSummaryView> summaries,
            OrderPreviewView selectedDetail
    ) {
    }
}

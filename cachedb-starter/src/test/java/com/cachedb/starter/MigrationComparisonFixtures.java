package com.reactor.cachedb.starter;

import java.time.Instant;
import java.util.List;

final class MigrationComparisonFixtures {

    private MigrationComparisonFixtures() {
    }

    static MigrationComparisonRunner.Result readyComparisonResult() {
        MigrationPlanner.Result plannerResult = plannerResult(false, false);
        MigrationComparisonRunner.Result comparison = new MigrationComparisonRunner.Result(
                new MigrationComparisonRunner.Request(
                        plannerResult.request(),
                        false,
                        true,
                        50,
                        25,
                        10,
                        "",
                        2,
                        1,
                        3,
                        50,
                        "",
                        ""
                ).normalize(),
                plannerResult,
                "projection:CustomerOrderSummaryHot",
                "SELECT * FROM customer_order WHERE customer_id = :sample_root_id ORDER BY order_date DESC LIMIT :page_size",
                null,
                new MigrationComparisonRunner.Metrics(4L, 8_000L, 9_500L, 10_000L, 25.0d),
                new MigrationComparisonRunner.Metrics(4L, 7_000L, 8_500L, 9_000L, 25.0d),
                List.of(
                        new MigrationComparisonRunner.SampleComparison("customer-1", 10, 10, List.of("101", "102"), List.of("101", "102"), true),
                        new MigrationComparisonRunner.SampleComparison("customer-2", 10, 10, List.of("201", "202"), List.of("201", "202"), true)
                ),
                List.of("Used warmed hot set."),
                List.of(),
                Instant.parse("2026-04-12T08:00:00Z"),
                Instant.parse("2026-04-12T08:00:02Z"),
                2_000L,
                null
        );
        return new MigrationComparisonRunner.Result(
                comparison.request(),
                comparison.plan(),
                comparison.cacheRouteLabel(),
                comparison.baselineSqlTemplate(),
                comparison.warmResult(),
                comparison.baselineMetrics(),
                comparison.cacheMetrics(),
                comparison.sampleComparisons(),
                comparison.notes(),
                comparison.warnings(),
                comparison.startedAt(),
                comparison.completedAt(),
                comparison.durationMillis(),
                MigrationComparisonAssessment.evaluate(comparison)
        );
    }

    private static MigrationPlanner.Result plannerResult(boolean projectionRequired, boolean rankedProjectionRequired) {
        MigrationPlanner.Request request = new MigrationPlanner.Request(
                "customer-orders",
                "customer_account",
                "customer_id",
                "customer_order",
                "order_id",
                "customer_id",
                "order_date",
                "DESC",
                100L,
                1_000L,
                100L,
                1_000L,
                2,
                3,
                projectionRequired,
                false,
                rankedProjectionRequired,
                false,
                true,
                false,
                true,
                true,
                true
        );
        return new MigrationPlanner().plan(request);
    }
}

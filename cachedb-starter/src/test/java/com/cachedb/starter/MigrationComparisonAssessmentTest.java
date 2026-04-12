package com.reactor.cachedb.starter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationComparisonAssessmentTest {

    @Test
    void shouldMarkRouteReadyWhenParityAndLatencyEnvelopeAreHealthy() {
        MigrationComparisonAssessment.Result assessment = MigrationComparisonAssessment.evaluate(comparisonResult(
                "projection:CustomerOrderSummaryHot",
                plannerResult(false, false),
                metrics(8_000L, 9_500L),
                metrics(7_000L, 8_500L),
                List.of(
                        sample("customer-1", true),
                        sample("customer-2", true)
                ),
                List.of(),
                List.of("Used warmed hot set."),
                null
        ));

        assertEquals(MigrationComparisonAssessment.Readiness.READY, assessment.readiness());
        assertEquals(MigrationComparisonAssessment.ParityStatus.EXACT, assessment.parityStatus());
        assertEquals(MigrationComparisonAssessment.PerformanceStatus.FASTER, assessment.performanceStatus());
        assertTrue(assessment.blockers().isEmpty());
        assertTrue(assessment.nextSteps().stream().anyMatch(step -> step.contains("cutover checklist")));
    }

    @Test
    void shouldBlockCutoverWhenParityFailsAndRouteFallsBack() {
        MigrationComparisonAssessment.Result assessment = MigrationComparisonAssessment.evaluate(comparisonResult(
                "entity:CustomerOrder",
                plannerResult(true, true),
                metrics(10_000L, 11_000L),
                metrics(60_000L, 4_500_000L),
                List.of(
                        sample("customer-1", false),
                        sample("customer-2", true)
                ),
                List.of("CacheDB comparison fell back to an entity query because no matching projection was registered."),
                List.of(),
                null
        ));

        assertEquals(MigrationComparisonAssessment.Readiness.NOT_READY, assessment.readiness());
        assertEquals(MigrationComparisonAssessment.ParityStatus.PARTIAL, assessment.parityStatus());
        assertEquals(MigrationComparisonAssessment.PerformanceStatus.BLOCKER, assessment.performanceStatus());
        assertTrue(assessment.blockers().stream().anyMatch(item -> item.contains("projection route")));
        assertTrue(assessment.blockers().stream().anyMatch(item -> item.contains("did not fully match PostgreSQL")));
    }

    private MigrationComparisonRunner.Result comparisonResult(
            String routeLabel,
            MigrationPlanner.Result plannerResult,
            MigrationComparisonRunner.Metrics baselineMetrics,
            MigrationComparisonRunner.Metrics cacheMetrics,
            List<MigrationComparisonRunner.SampleComparison> sampleComparisons,
            List<String> warnings,
            List<String> notes,
            MigrationWarmRunner.Result warmResult
    ) {
        return new MigrationComparisonRunner.Result(
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
                routeLabel,
                "SELECT * FROM customer_order WHERE customer_id = :sample_root_id ORDER BY order_date DESC LIMIT :page_size",
                warmResult,
                baselineMetrics,
                cacheMetrics,
                sampleComparisons,
                notes,
                warnings,
                Instant.parse("2026-04-12T08:00:00Z"),
                Instant.parse("2026-04-12T08:00:02Z"),
                2_000L,
                null
        );
    }

    private MigrationPlanner.Result plannerResult(boolean projectionRequired, boolean rankedProjectionRequired) {
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

    private MigrationComparisonRunner.Metrics metrics(long averageLatencyNanos, long p95LatencyNanos) {
        return new MigrationComparisonRunner.Metrics(4L, averageLatencyNanos, p95LatencyNanos, p95LatencyNanos, 25.0d);
    }

    private MigrationComparisonRunner.SampleComparison sample(String label, boolean exactMatch) {
        return new MigrationComparisonRunner.SampleComparison(
                label,
                10,
                10,
                List.of("101", "102"),
                exactMatch ? List.of("101", "102") : List.of("102", "101"),
                exactMatch
        );
    }
}

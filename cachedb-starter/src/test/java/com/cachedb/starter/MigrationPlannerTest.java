package com.reactor.cachedb.starter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationPlannerTest {

    private final MigrationPlanner planner = new MigrationPlanner();

    @Test
    void shouldRecommendBoundedSummaryProjectionForHighFanoutTimeline() {
        MigrationPlanner.Request request = new MigrationPlanner.Request(
                "customer-orders",
                "customer",
                "customer_id",
                "orders",
                "order_id",
                "customer_id",
                "order_date",
                "DESC",
                100_000L,
                8_000_000L,
                120L,
                2_500L,
                100,
                1_000,
                true,
                false,
                false,
                false,
                true,
                false,
                true,
                true,
                true
        );

        MigrationPlanner.Result result = planner.plan(request);

        assertTrue(result.projectionRequired());
        assertFalse(result.rankedProjectionRequired());
        assertTrue(result.summaryFirstRequired());
        assertTrue(result.boundedRedisWindowRequired());
        assertTrue(result.summaryProjectionName().contains("SummaryHot"));
        assertTrue(result.recommendedRedisArtifacts().stream().anyMatch(item -> item.contains("customer_id")));
        assertTrue(result.sampleWarmSql().contains("ROW_NUMBER() OVER"));
        assertTrue(result.sampleWarmSql().contains("PARTITION BY customer_id"));
        assertTrue(result.sampleRootWarmSql().contains(":referenced_root_ids"));
    }

    @Test
    void shouldRequireRankedProjectionForGlobalSortedRoute() {
        MigrationPlanner.Request request = new MigrationPlanner.Request(
                "top-customers",
                "customer",
                "customer_id",
                "orders",
                "order_id",
                "customer_id",
                "order_amount",
                "DESC",
                500_000L,
                25_000_000L,
                15L,
                800L,
                50,
                500,
                true,
                false,
                true,
                true,
                true,
                false,
                true,
                true,
                true
        );

        MigrationPlanner.Result result = planner.plan(request);

        assertTrue(result.projectionRequired());
        assertTrue(result.rankedProjectionRequired());
        assertTrue(result.rankedProjectionName().contains("RankedHot"));
        assertTrue(result.rankFieldName().equals("rank_score"));
        assertTrue(result.recommendedApiShapes().stream().anyMatch(item -> item.contains("rank_score")));
        assertTrue(result.warnings().stream().anyMatch(item -> item.toLowerCase().contains("global sorted")));
        assertFalse(result.sampleWarmSql().contains("PARTITION BY"));
        assertTrue(result.sampleWarmSql().contains("ORDER BY order_amount DESC"));
    }
}

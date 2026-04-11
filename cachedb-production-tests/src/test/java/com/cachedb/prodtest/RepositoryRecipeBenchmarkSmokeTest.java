package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkReport;
import com.reactor.cachedb.prodtest.scenario.RepositoryRecipeBenchmarkRunner;
import com.reactor.cachedb.prodtest.scenario.RepositoryRecipeMode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class RepositoryRecipeBenchmarkSmokeTest {

    @Test
    void shouldProduceThreeRecipeModesWithComparableFeatureSet() {
        RepositoryRecipeBenchmarkReport report = new RepositoryRecipeBenchmarkRunner().run(100, 500);

        Assertions.assertEquals(3, report.modeReports().size());
        Assertions.assertEquals(
                EnumSet.allOf(RepositoryRecipeMode.class),
                report.modeReports().stream().map(modeReport -> modeReport.mode()).collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(RepositoryRecipeMode.class)))
        );
        Assertions.assertTrue(report.disclaimer().contains("does not benchmark external Hibernate/JPA runtime"));
        Assertions.assertTrue(report.comparedOperations().contains("activeCustomers"));
        Assertions.assertTrue(report.comparedOperations().contains("topCustomerOrdersSummary"));
        Assertions.assertTrue(report.modeReports().stream().allMatch(reportItem -> reportItem.operationReports().size() == 5));
        Assertions.assertTrue(report.modeReports().stream().allMatch(reportItem -> reportItem.averageLatencyNanos() >= 0L));
        Assertions.assertFalse(report.fastestAverageMode().isBlank());
        Map<RepositoryRecipeMode, com.reactor.cachedb.prodtest.scenario.RepositoryRecipeModeReport> byMode = report.modeReports().stream()
                .collect(Collectors.toMap(reportItem -> reportItem.mode(), Function.identity()));
        com.reactor.cachedb.prodtest.scenario.RepositoryRecipeModeReport minimal = byMode.get(RepositoryRecipeMode.MINIMAL_REPOSITORY);
        com.reactor.cachedb.prodtest.scenario.RepositoryRecipeModeReport generated = byMode.get(RepositoryRecipeMode.GENERATED_ENTITY_BINDING);
        com.reactor.cachedb.prodtest.scenario.RepositoryRecipeModeReport grouped = byMode.get(RepositoryRecipeMode.JPA_STYLE_DOMAIN_MODULE);
        Assertions.assertNotNull(minimal);
        Assertions.assertNotNull(generated);
        Assertions.assertNotNull(grouped);
        Assertions.assertTrue(
                minimal.averageLatencyNanos() < 100_000L,
                "Minimal repository usage should stay inside a low-overhead microbenchmark band."
        );
        Assertions.assertTrue(
                generated.averageLatencyNanos() < 120_000L,
                "Generated binding should stay inside the same low-overhead band."
        );
        Assertions.assertTrue(
                grouped.averageLatencyNanos() < 150_000L,
                "Grouped domain module ergonomics should remain inside a modest absolute overhead budget."
        );
        Assertions.assertTrue(
                generated.p95LatencyNanos() < 150_000L,
                "Generated binding p95 should stay inside a modest absolute overhead budget."
        );
        Assertions.assertTrue(
                grouped.p95LatencyNanos() < 200_000L,
                "Grouped domain module p95 should stay inside a modest absolute overhead budget."
        );
        Assertions.assertTrue(
                report.maxAverageSpreadPercent() < 5_000.0d,
                "Recipe benchmark drift should stay bounded enough to remain useful as a production evidence gate."
        );
    }
}

package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.EcommerceScenarioProfile;
import com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkCatalog;
import com.reactor.cachedb.prodtest.scenario.GuardrailProfileComparisonReport;
import com.reactor.cachedb.prodtest.scenario.GuardrailProfileComparisonRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public final class GuardrailProfileComparisonSmokeTest {

    @Test
    void comparisonRunnerShouldCompareProfilesForSameScenario() throws Exception {
        GuardrailProfileComparisonReport report = new GuardrailProfileComparisonRunner().run(
                "guardrail-profile-comparison-smoke",
                "guardrail-profile-comparison-smoke",
                List.of(smokeProfile(FullScaleBenchmarkCatalog.byName("campaign-push-spike-50k"))),
                0.10d,
                List.of("STANDARD", "BALANCED", "AGGRESSIVE")
        );

        Assertions.assertEquals(1, report.scenarioCount());
        Assertions.assertEquals(3, report.comparedProfiles().size());
        Assertions.assertEquals(3, report.totalRuns());
        Assertions.assertEquals(3, report.profileSummaries().size());
        Assertions.assertFalse(report.bestOverallProfile().isBlank());
        Assertions.assertEquals(3, report.scenarioComparisons().get(0).profileRuns().size());
    }

    private EcommerceScenarioProfile smokeProfile(EcommerceScenarioProfile profile) {
        return new EcommerceScenarioProfile(
                profile.name() + "-guardrail-smoke",
                profile.kind(),
                profile.description(),
                500,
                1,
                2,
                Math.min(profile.customerCount(), 200),
                Math.min(profile.productCount(), 100),
                Math.min(profile.hotProductSetSize(), 10),
                profile.browsePercent(),
                profile.productLookupPercent(),
                profile.cartWritePercent(),
                profile.inventoryReservePercent(),
                profile.checkoutPercent(),
                profile.customerTouchPercent(),
                1,
                Math.min(profile.writeBehindBatchSize(), 20),
                Math.min(profile.hotEntityLimit(), 200),
                Math.min(profile.pageSize(), 20),
                Math.min(profile.entityTtlSeconds(), 30),
                Math.min(profile.pageTtlSeconds(), 15)
        );
    }
}

package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.BenchmarkSuiteReport;
import com.reactor.cachedb.prodtest.scenario.EcommerceScenarioProfile;
import com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkCatalog;
import com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkSuiteRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public final class FullScaleBenchmarkSuiteSmokeTest {

    @Test
    void suiteRunnerShouldAggregateMultipleReduced50kScenarios() throws Exception {
        BenchmarkSuiteReport report = new FullScaleBenchmarkSuiteRunner().run(
                List.of(
                        smokeProfile(FullScaleBenchmarkCatalog.byName("campaign-push-spike-50k")),
                        smokeProfile(FullScaleBenchmarkCatalog.byName("write-behind-backpressure-50k"))
                ),
                0.01d
        );

        Assertions.assertEquals(2, report.scenarioCount());
        Assertions.assertTrue(report.totalExecutedOperations() > 0);
        Assertions.assertTrue(report.totalWriteOperations() > 0);
        Assertions.assertTrue(report.worstP95LatencyMicros() >= 0);
        Assertions.assertTrue(report.worstP99LatencyMicros() >= 0);
        Assertions.assertFalse(report.scenarioReports().isEmpty());
    }

    private EcommerceScenarioProfile smokeProfile(EcommerceScenarioProfile profile) {
        return new EcommerceScenarioProfile(
                profile.name() + "-suite-smoke",
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

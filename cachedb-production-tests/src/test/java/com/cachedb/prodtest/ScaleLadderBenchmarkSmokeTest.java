package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.EcommerceScenarioProfile;
import com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkCatalog;
import com.reactor.cachedb.prodtest.scenario.ScaleLadderBenchmarkReport;
import com.reactor.cachedb.prodtest.scenario.ScaleLadderBenchmarkRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public final class ScaleLadderBenchmarkSmokeTest {

    @Test
    void scaleLadderRunnerShouldAggregateAcrossMultipleScaleFactors() throws Exception {
        ScaleLadderBenchmarkReport report = new ScaleLadderBenchmarkRunner().run(
                List.of(smokeProfile(FullScaleBenchmarkCatalog.byName("campaign-push-spike-50k"))),
                List.of(0.10d, 0.25d)
        );

        Assertions.assertEquals(2, report.runCount());
        Assertions.assertEquals(2, report.scaleFactors().size());
        Assertions.assertTrue(report.totalExecutedOperations() > 0);
        Assertions.assertTrue(report.worstP99LatencyMicros() >= 0);
    }

    private EcommerceScenarioProfile smokeProfile(EcommerceScenarioProfile profile) {
        return new EcommerceScenarioProfile(
                profile.name() + "-ladder-smoke",
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

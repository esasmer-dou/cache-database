package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.EcommerceProductionScenarioRunner;
import com.reactor.cachedb.prodtest.scenario.EcommerceScenarioProfile;
import com.reactor.cachedb.prodtest.scenario.ScenarioCatalog;
import com.reactor.cachedb.prodtest.scenario.ScenarioReport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class EcommerceProductionScenarioSmokeTest {

    @Test
    void loadScenarioShouldExecuteReadAndWriteTraffic() throws Exception {
        EcommerceProductionScenarioRunner runner = new EcommerceProductionScenarioRunner();
        ScenarioReport report = runner.run(smokeProfile(ScenarioCatalog.byName("campaign-push-spike")), 0.01d);

        Assertions.assertTrue(report.executedOperations() > 0);
        Assertions.assertTrue(report.readOperations() > 0);
        Assertions.assertTrue(report.writeOperations() > 0);
        Assertions.assertTrue(report.averageLatencyMicros() >= 0);
    }

    @Test
    void breakerScenarioShouldProduceObservableStressSignals() throws Exception {
        EcommerceProductionScenarioRunner runner = new EcommerceProductionScenarioRunner();
        ScenarioReport report = runner.run(smokeProfile(ScenarioCatalog.byName("write-behind-backpressure-breaker")), 0.01d);

        Assertions.assertTrue(report.executedOperations() > 0);
        Assertions.assertTrue(report.writeOperations() > 0);
        Assertions.assertTrue(report.writeBehindStreamLength() >= 0);
        Assertions.assertTrue(report.plannerLearnedStatsCount() >= 0);
    }

    private EcommerceScenarioProfile smokeProfile(EcommerceScenarioProfile profile) {
        return new EcommerceScenarioProfile(
                profile.name() + "-smoke",
                profile.kind(),
                profile.description(),
                Math.min(profile.targetTransactionsPerSecond(), 500),
                1,
                Math.min(profile.workerThreads(), 2),
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

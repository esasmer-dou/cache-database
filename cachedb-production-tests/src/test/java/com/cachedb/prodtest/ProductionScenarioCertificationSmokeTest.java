package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.ProductionScenarioCertificationReport;
import com.reactor.cachedb.prodtest.scenario.ProductionScenarioCertificationRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class ProductionScenarioCertificationSmokeTest {

    @Test
    void productionScenarioCertificationShouldPassFocusedRouteAndOutboxGates() throws Exception {
        ProductionScenarioCertificationReport report = new ProductionScenarioCertificationRunner().run();

        Assertions.assertTrue(report.passed());
        Assertions.assertEquals(5, report.gates().size());
        Assertions.assertTrue(report.gates().stream().allMatch(gate -> gate.passed()));
    }
}

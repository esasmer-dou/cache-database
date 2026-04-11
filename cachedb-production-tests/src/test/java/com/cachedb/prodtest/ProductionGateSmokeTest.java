package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.ProductionGateReport;
import com.reactor.cachedb.prodtest.scenario.ProductionGateRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class ProductionGateSmokeTest {

    @Test
    void productionGateShouldProduceDecisionReport() throws Exception {
        System.setProperty("cachedb.prod.certification.scaleFactor", "0.005");
        System.setProperty("cachedb.prod.certification.minTps", "1");
        System.setProperty("cachedb.prod.certification.maxBacklog", "5000");
        ProductionGateReport report = new ProductionGateRunner().run();
        Assertions.assertNotNull(report);
        Assertions.assertFalse(report.checks().isEmpty());
        Assertions.assertNotNull(report.overallStatus());
    }
}

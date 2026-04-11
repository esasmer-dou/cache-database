package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.ProductionCertificationReport;
import com.reactor.cachedb.prodtest.scenario.ProductionCertificationRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class ProductionCertificationSmokeTest {

    @Test
    void certificationRunnerShouldProduceGateReport() throws Exception {
        System.setProperty("cachedb.prod.certification.scaleFactor", "0.005");
        System.setProperty("cachedb.prod.certification.minTps", "1");
        System.setProperty("cachedb.prod.certification.maxBacklog", "5000");
        ProductionCertificationReport report = new ProductionCertificationRunner().run();

        Assertions.assertNotNull(report);
        Assertions.assertFalse(report.gates().isEmpty());
        Assertions.assertNotNull(report.restartRecovery());
        Assertions.assertNotNull(report.crashReplayChaos());
        Assertions.assertNotNull(report.faultInjection());
        Assertions.assertTrue(report.benchmarkReport().executedOperations() > 0);
    }
}

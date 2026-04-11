package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.FaultInjectionSuiteReport;
import com.reactor.cachedb.prodtest.scenario.FaultInjectionSuiteRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class FaultInjectionSuiteSmokeTest {

    @Test
    void faultInjectionSuiteShouldProduceScenarioReport() throws Exception {
        FaultInjectionSuiteReport report = new FaultInjectionSuiteRunner().run();
        Assertions.assertEquals(4, report.scenarioCount());
        Assertions.assertFalse(report.scenarios().isEmpty());
    }
}

package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.RestartRecoverySuiteReport;
import com.reactor.cachedb.prodtest.scenario.RestartRecoverySuiteRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class RestartRecoverySuiteSmokeTest {

    @Test
    void restartRecoverySuiteShouldProduceCycleReport() throws Exception {
        RestartRecoverySuiteReport report = new RestartRecoverySuiteRunner().run(1);
        Assertions.assertEquals(1, report.cycleCount());
        Assertions.assertFalse(report.cycles().isEmpty());
    }
}

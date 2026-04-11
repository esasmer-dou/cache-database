package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.CrashReplayChaosSuiteReport;
import com.reactor.cachedb.prodtest.scenario.CrashReplayChaosSuiteRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class CrashReplayChaosSuiteSmokeTest {

    @Test
    void crashReplayChaosSuiteShouldProduceScenarioReport() throws Exception {
        CrashReplayChaosSuiteReport report = new CrashReplayChaosSuiteRunner().run();
        Assertions.assertEquals(3, report.scenarioCount());
        Assertions.assertFalse(report.scenarios().isEmpty());
    }
}

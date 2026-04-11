package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.ProductionSoakReport;
import com.reactor.cachedb.prodtest.scenario.ProductionSoakRunner;
import com.reactor.cachedb.prodtest.scenario.ScenarioCatalog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class ProductionSoakSmokeTest {

    @Test
    void soakRunnerShouldProduceEnvelopeReport() throws Exception {
        ProductionSoakReport report = new ProductionSoakRunner().run(ScenarioCatalog.byName("campaign-push-spike"), 0.005d, 2);
        Assertions.assertEquals(2, report.iterationCount());
        Assertions.assertTrue(report.maxBacklog() >= 0);
        Assertions.assertTrue(report.maxRedisMemoryBytes() >= 0);
    }
}

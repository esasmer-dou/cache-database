package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.EcommerceScenarioKind;
import com.reactor.cachedb.prodtest.scenario.ScenarioCatalog;
import com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScenarioCatalogOverridesTest {

    @Test
    void shouldOverrideBaseScenarioCatalog() {
        String previous = System.getProperty("cachedb.prod.catalog.scenarios");
        System.setProperty(
                "cachedb.prod.catalog.scenarios",
                "demo-scenario;LOAD;override desc;123;11;7;100;80;5;10;20;30;15;15;10;2;50;200;25;60;30"
        );
        try {
            assertEquals(1, ScenarioCatalog.all().size());
            assertEquals("demo-scenario", ScenarioCatalog.all().get(0).name());
            assertEquals(EcommerceScenarioKind.LOAD, ScenarioCatalog.all().get(0).kind());
            assertEquals(123, ScenarioCatalog.all().get(0).targetTransactionsPerSecond());
        } finally {
            restore("cachedb.prod.catalog.scenarios", previous);
        }
    }

    @Test
    void shouldOverrideRepresentativeCatalogNames() {
        String previousFull = System.getProperty("cachedb.prod.catalog.fullScaleScenarios");
        String previousRepresentative = System.getProperty("cachedb.prod.catalog.representativeScenarioNames");
        System.setProperty(
                "cachedb.prod.catalog.fullScaleScenarios",
                "alpha-50k;LOAD;alpha desc;100;10;4;10;10;2;20;20;20;20;10;10;1;10;10;5;60;30|"
                        + "beta-50k;BREAK;beta desc;200;20;8;20;20;4;15;15;20;20;20;10;2;20;20;10;60;30"
        );
        System.setProperty("cachedb.prod.catalog.representativeScenarioNames", "beta-50k");
        try {
            List<?> profiles = FullScaleBenchmarkCatalog.all();
            assertEquals(2, profiles.size());
            assertEquals("beta-50k", com.reactor.cachedb.prodtest.scenario.RepresentativeCapacityBenchmarkCatalog.all().get(0).name());
        } finally {
            restore("cachedb.prod.catalog.fullScaleScenarios", previousFull);
            restore("cachedb.prod.catalog.representativeScenarioNames", previousRepresentative);
        }
    }

    private void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}

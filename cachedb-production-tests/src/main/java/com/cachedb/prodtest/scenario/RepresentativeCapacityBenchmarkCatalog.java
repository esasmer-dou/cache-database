package com.reactor.cachedb.prodtest.scenario;

import java.util.List;

public final class RepresentativeCapacityBenchmarkCatalog {

    private static final String DEFAULT_SCENARIO_NAMES =
            "campaign-push-spike-50k,weekend-browse-storm-50k,write-behind-backpressure-50k";

    private RepresentativeCapacityBenchmarkCatalog() {
    }

    public static List<EcommerceScenarioProfile> all() {
        return ScenarioCatalogOverrides.names("cachedb.prod.catalog.representativeScenarioNames", DEFAULT_SCENARIO_NAMES).stream()
                .map(FullScaleBenchmarkCatalog::byName)
                .toList();
    }
}

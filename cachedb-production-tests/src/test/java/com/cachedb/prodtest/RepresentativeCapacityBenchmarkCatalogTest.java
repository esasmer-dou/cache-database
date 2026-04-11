package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.RepresentativeCapacityBenchmarkCatalog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class RepresentativeCapacityBenchmarkCatalogTest {

    @Test
    void representativeCatalogShouldExposeThreeDistinctWorkloadShapes() {
        var profiles = RepresentativeCapacityBenchmarkCatalog.all();

        Assertions.assertEquals(3, profiles.size());
        Assertions.assertTrue(profiles.stream().allMatch(profile -> profile.targetTransactionsPerSecond() == 50_000));
        Assertions.assertTrue(profiles.stream().anyMatch(profile -> profile.browsePercent() >= 50));
        Assertions.assertTrue(profiles.stream().anyMatch(profile -> profile.checkoutPercent() >= 25));
        Assertions.assertTrue(profiles.stream().anyMatch(profile -> profile.writeBehindWorkerThreads() <= 4));
    }
}

package com.reactor.cachedb.prodtest;

import com.reactor.cachedb.prodtest.scenario.EcommerceScenarioProfile;
import com.reactor.cachedb.prodtest.scenario.FullScaleBenchmarkCatalog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class FullScaleBenchmarkCatalogTest {

    @Test
    void fullScaleCatalogShouldExposeMultipleDistinct50kScenarios() {
        List<EcommerceScenarioProfile> profiles = FullScaleBenchmarkCatalog.all();

        Assertions.assertTrue(profiles.size() >= 8);
        Assertions.assertTrue(profiles.stream().allMatch(profile -> profile.targetTransactionsPerSecond() == 50_000));
        Assertions.assertTrue(profiles.stream().map(EcommerceScenarioProfile::name).allMatch(name -> name.endsWith("-50k")));

        Set<String> names = profiles.stream().map(EcommerceScenarioProfile::name).collect(Collectors.toSet());
        Assertions.assertEquals(profiles.size(), names.size());

        Assertions.assertTrue(profiles.stream().anyMatch(profile -> profile.browsePercent() >= 45));
        Assertions.assertTrue(profiles.stream().anyMatch(profile -> profile.checkoutPercent() >= 25));
        Assertions.assertTrue(profiles.stream().anyMatch(profile -> profile.inventoryReservePercent() >= 30));
    }
}

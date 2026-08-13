package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.starter.CacheDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Objects;

public final class CacheDbStartupReporter implements SmartInitializingSingleton {
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheDbStartupReporter.class);

    private final CacheDatabase cacheDatabase;
    private final CacheDbProviderInfo providerInfo;
    private final CacheDbSpringProperties properties;
    private final CacheDbRouteInventory routeInventory;

    public CacheDbStartupReporter(
            CacheDatabase cacheDatabase,
            CacheDbProviderInfo providerInfo,
            CacheDbSpringProperties properties
    ) {
        this(cacheDatabase, providerInfo, properties, CacheDbRouteInventory.empty());
    }

    public CacheDbStartupReporter(
            CacheDatabase cacheDatabase,
            CacheDbProviderInfo providerInfo,
            CacheDbSpringProperties properties,
            CacheDbRouteInventory routeInventory
    ) {
        this.cacheDatabase = Objects.requireNonNull(cacheDatabase, "cacheDatabase");
        this.providerInfo = Objects.requireNonNull(providerInfo, "providerInfo");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.routeInventory = Objects.requireNonNull(routeInventory, "routeInventory");
    }

    @Override
    public void afterSingletonsInstantiated() {
        int entities = cacheDatabase.entityRegistry().all().size();
        int projections = cacheDatabase.entityRegistry().all().stream()
                .mapToInt(binding -> cacheDatabase.entityRegistry().projections(binding.metadata().entityName()).size())
                .sum();
        LOGGER.info(
                "CacheDB ready instanceId={} provider={} entities={} projections={} repositories={} routes={} hotRoutes={} warmRoutes={} hotPopulation={} hotMemoryBudgetBytes={} unbudgetedHotRoutes={} registration={} scheduledWarm={} adminUi={}",
                cacheDatabase.instanceId(),
                providerInfo.id(),
                entities,
                projections,
                routeInventory.repositoryCount(),
                routeInventory.routeCount(),
                routeInventory.count(com.reactor.cachedb.core.route.RepositoryRouteKind.HOT),
                routeInventory.count(com.reactor.cachedb.core.route.RepositoryRouteKind.WARM),
                routeInventory.hotPopulationCounts(),
                routeInventory.hotRouteAssessment().declaredMemoryBudgetBytes(),
                routeInventory.hotRouteAssessment().unbudgetedRoutes(),
                properties.getRegistration().isEnabled(),
                properties.getScheduledWarm().isEnabled(),
                properties.getAdmin().isHttpEnabled()
        );
    }
}

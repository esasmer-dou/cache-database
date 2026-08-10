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

    public CacheDbStartupReporter(
            CacheDatabase cacheDatabase,
            CacheDbProviderInfo providerInfo,
            CacheDbSpringProperties properties
    ) {
        this.cacheDatabase = Objects.requireNonNull(cacheDatabase, "cacheDatabase");
        this.providerInfo = Objects.requireNonNull(providerInfo, "providerInfo");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void afterSingletonsInstantiated() {
        int entities = cacheDatabase.entityRegistry().all().size();
        int projections = cacheDatabase.entityRegistry().all().stream()
                .mapToInt(binding -> cacheDatabase.entityRegistry().projections(binding.metadata().entityName()).size())
                .sum();
        LOGGER.info(
                "CacheDB ready instanceId={} provider={} entities={} projections={} registration={} scheduledWarm={} adminUi={}",
                cacheDatabase.instanceId(),
                providerInfo.id(),
                entities,
                projections,
                properties.getRegistration().isEnabled(),
                properties.getScheduledWarm().isEnabled(),
                properties.getAdmin().isHttpEnabled()
        );
    }
}

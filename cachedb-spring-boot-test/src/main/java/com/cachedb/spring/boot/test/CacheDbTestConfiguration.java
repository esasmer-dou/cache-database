package com.reactor.cachedb.spring.boot.test;

import com.reactor.cachedb.spring.boot.CacheDbRouteInventory;
import com.reactor.cachedb.starter.CacheDatabase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class CacheDbTestConfiguration {
    @Bean
    public CacheDbTestProbe cacheDbTestProbe(
            CacheDatabase cacheDatabase,
            ObjectProvider<CacheDbRouteInventory> routeInventory
    ) {
        return new CacheDbTestProbe(
                cacheDatabase,
                routeInventory.getIfAvailable(CacheDbRouteInventory::empty)
        );
    }

    /** @deprecated Direct factory calls should supply the generated route inventory. */
    @Deprecated(forRemoval = false)
    public CacheDbTestProbe cacheDbTestProbe(CacheDatabase cacheDatabase) {
        return new CacheDbTestProbe(cacheDatabase);
    }
}

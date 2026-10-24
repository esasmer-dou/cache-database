package com.example.cachedb.sample.catalog;

import com.reactor.cachedb.annotations.CacheJobHandler;
import com.reactor.cachedb.spring.boot.CacheDistributedJobContext;
import com.reactor.cachedb.spring.boot.CacheDistributedJobDefinition;
import com.reactor.cachedb.spring.boot.snapshot.SnapshotJobs;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
public final class CatalogRefreshJobHandler {
    public static final CacheDistributedJobDefinition<CatalogRefreshCommand> DEFINITION =
            CacheDistributedJobDefinition.of("sample.catalog.refresh", CatalogRefreshCommand.class);
    private final SnapshotJobs snapshots;

    public CatalogRefreshJobHandler(SnapshotJobs snapshots) {
        this.snapshots = snapshots;
    }

    public CacheDistributedJobDefinition<CatalogRefreshCommand> definition() {
        return DEFINITION;
    }

    @CacheJobHandler("sample.catalog.refresh")
    public Object execute(CacheDistributedJobContext context, CatalogRefreshCommand command) {
        var result =
                command.ids().isEmpty()
                        ? snapshots.refresh(CatalogConfiguration.PLAN, true)
                        : snapshots.refreshRoots(
                                CatalogConfiguration.PLAN,
                                command.ids().stream().map(Object::toString).toList());
        if ("BUSY".equals(result.status())) {
            throw new IllegalStateException(
                    "Catalog preparation is busy; retry this explicit job later");
        }
        return result;
    }
}

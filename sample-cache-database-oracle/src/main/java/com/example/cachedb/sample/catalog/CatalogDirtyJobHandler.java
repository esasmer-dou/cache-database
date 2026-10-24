package com.example.cachedb.sample.catalog;

import com.reactor.cachedb.annotations.CacheJobHandler;
import com.reactor.cachedb.spring.boot.CacheDistributedJobDefinition;
import com.reactor.cachedb.spring.boot.snapshot.SnapshotJobs;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** SQL reconciliation is explicit worker work, never a hidden read-endpoint side effect. */
@Component
@ConditionalOnProperty(prefix="sample.demo", name="write-tools-enabled", havingValue="true")
public final class CatalogDirtyJobHandler {
    public static final CacheDistributedJobDefinition<CatalogRefreshCommand> DEFINITION =
            CacheDistributedJobDefinition.of("sample.catalog.flush-dirty", CatalogRefreshCommand.class);
    private final SnapshotJobs snapshots;
    public CatalogDirtyJobHandler(SnapshotJobs snapshots) { this.snapshots = snapshots; }

    @CacheJobHandler("sample.catalog.flush-dirty")
    public Object flush(CatalogRefreshCommand command) {
        if (!command.ids().isEmpty()) throw new IllegalArgumentException("Flush consumes the already marked roots, not an input ID list");
        return snapshots.flushDirty(CatalogConfiguration.PLAN).<Object>map(value -> value)
                .orElseGet(() -> Map.of("status", "NO_CHANGES"));
    }
}

package com.example.cachedb.sample.catalog;

import java.util.List;

/** Empty IDs request a bounded complete preparation; nonempty IDs request explicit scoped SQL. */
public record CatalogRefreshCommand(List<Long> ids) {
    public CatalogRefreshCommand {
        if (ids == null || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id < 1)) {
            throw new IllegalArgumentException("ids must contain 0..100 positive product IDs");
        }
        ids = ids.stream().distinct().toList();
    }
}

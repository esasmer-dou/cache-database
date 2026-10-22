package com.example.cachedb.sample.catalog;

import com.reactor.cachedb.spring.boot.snapshot.SnapshotLookup;
import com.reactor.cachedb.spring.boot.snapshot.SnapshotStatus;

import java.util.List;

/** Do not duplicate the raw JSON string in the HTTP response. */
public record CatalogLookup(
        String id,
        SnapshotStatus status,
        List<CatalogCard> values,
        long refreshedAt,
        long ageMillis,
        String generation) {
    public static CatalogLookup from(SnapshotLookup<CatalogCard> value) {
        return new CatalogLookup(
                value.id(),
                value.status(),
                value.values(),
                value.refreshedAt(),
                value.ageMillis(),
                value.generation());
    }
}

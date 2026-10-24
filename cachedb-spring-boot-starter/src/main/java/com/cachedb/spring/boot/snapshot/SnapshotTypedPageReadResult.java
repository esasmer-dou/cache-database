package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.repository.CursorPage;
import java.util.List;

public record SnapshotTypedPageReadResult<V>(List<SnapshotLookup<V>> items, String nextCursor) {
    public SnapshotTypedPageReadResult {
        if (items.size() > 1000) throw new IllegalArgumentException("Snapshot page limit exceeded");
        items = List.copyOf(items);
    }
    public CursorPage<SnapshotLookup<V>> page() { return new CursorPage<>(items, nextCursor); }
}

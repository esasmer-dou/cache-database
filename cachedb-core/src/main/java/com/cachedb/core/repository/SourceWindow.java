package com.reactor.cachedb.core.repository;

import java.util.List;

/** Explicit durable-source result. It never implies that the rows were admitted to Redis. */
public record SourceWindow<T>(List<T> items, String nextCursor) {
    public SourceWindow {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor;
    }
}

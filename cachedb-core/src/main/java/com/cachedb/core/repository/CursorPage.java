package com.reactor.cachedb.core.repository;

import java.util.List;
import java.util.Optional;

/**
 * Transport-friendly keyset page. It preserves the opaque continuation cursor
 * without exposing storage-specific paging details.
 */
public record CursorPage<T>(List<T> items, String nextCursor) {
    public CursorPage {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor.trim();
    }

    public static <T> CursorPage<T> from(WindowSlice<T> window) {
        if (window == null) {
            throw new IllegalArgumentException("window must not be null");
        }
        return new CursorPage<>(window.items(), window.nextCursor());
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public boolean hasNext() {
        return nextCursor != null;
    }

    public Optional<WindowRequest> nextRequest(int limit) {
        WindowRequest.requireValidLimit(limit);
        return hasNext()
                ? Optional.of(WindowRequest.after(nextCursor, limit))
                : Optional.empty();
    }
}

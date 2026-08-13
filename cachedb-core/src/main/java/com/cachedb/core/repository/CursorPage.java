package com.reactor.cachedb.core.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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

    /** Preserves the caller's validated page limit while advancing the cursor. */
    public Optional<WindowRequest> nextRequest(WindowRequest currentRequest) {
        if (currentRequest == null) {
            throw new IllegalArgumentException("currentRequest must not be null");
        }
        return hasNext()
                ? Optional.of(currentRequest.continueAfter(nextCursor))
                : Optional.empty();
    }

    /** Maps transport items while preserving the opaque continuation cursor. */
    public <R> CursorPage<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (items.isEmpty()) {
            return new CursorPage<>(List.of(), nextCursor);
        }
        ArrayList<R> mapped = new ArrayList<>(items.size());
        for (T item : items) {
            mapped.add(Objects.requireNonNull(mapper.apply(item), "mapper must not return null"));
        }
        return new CursorPage<>(mapped, nextCursor);
    }
}

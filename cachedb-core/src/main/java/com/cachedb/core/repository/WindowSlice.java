package com.reactor.cachedb.core.repository;

import java.util.List;
import java.util.Optional;

/**
 * Common cursor-window contract shared by Redis hot routes and explicit SQL
 * source routes. It intentionally exposes no offset pagination.
 */
public interface WindowSlice<T> {
    List<T> items();

    String nextCursor();

    default int size() {
        return items().size();
    }

    default boolean isEmpty() {
        return items().isEmpty();
    }

    default boolean hasNext() {
        return nextCursor() != null;
    }

    default Optional<WindowRequest> nextRequest(int limit) {
        WindowRequest.requireValidLimit(limit);
        return hasNext()
                ? Optional.of(WindowRequest.after(nextCursor(), limit))
                : Optional.empty();
    }
}

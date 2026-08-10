package com.reactor.cachedb.core.repository;

/** Bounded cursor request. Offset pagination is intentionally not part of the hot-route API. */
public record WindowRequest(int limit, String after) {
    public static final int MAX_LIMIT = 1_000;

    public WindowRequest {
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        after = after == null || after.isBlank() ? null : after.trim();
    }

    public static WindowRequest first(int limit) {
        return new WindowRequest(limit, null);
    }

    public static WindowRequest after(String cursor, int limit) {
        if (cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must not be blank");
        }
        return new WindowRequest(limit, cursor);
    }

    public int queryLimit() {
        return limit + 1;
    }
}

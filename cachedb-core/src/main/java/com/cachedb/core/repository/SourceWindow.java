package com.reactor.cachedb.core.repository;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;

/** Explicit durable-source result. It never implies that the rows were admitted to Redis. */
public record SourceWindow<T>(List<T> items, String nextCursor) implements WindowSlice<T> {
    public SourceWindow {
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor;
    }

    /** Converts this explicit durable-source result to a transport page. */
    public CursorPage<T> page() {
        return CursorPage.from(this);
    }

    /** Maps source rows without implying Redis admission or route coverage. */
    public <R> SourceWindow<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (items.isEmpty()) {
            return new SourceWindow<>(List.of(), nextCursor);
        }
        ArrayList<R> mapped = new ArrayList<>(items.size());
        for (T item : items) {
            mapped.add(Objects.requireNonNull(mapper.apply(item), "mapper must not return null"));
        }
        return new SourceWindow<>(mapped, nextCursor);
    }
}

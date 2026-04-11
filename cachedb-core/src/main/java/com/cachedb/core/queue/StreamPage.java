package com.reactor.cachedb.core.queue;

import java.util.List;

public record StreamPage<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore
) {
}

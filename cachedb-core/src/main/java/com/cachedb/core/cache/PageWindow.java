package com.reactor.cachedb.core.cache;

public record PageWindow(int pageNumber, int pageSize) {
    public int offset() {
        return Math.max(0, pageNumber) * pageSize;
    }
}

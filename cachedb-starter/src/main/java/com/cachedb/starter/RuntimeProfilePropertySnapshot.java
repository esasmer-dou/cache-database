package com.reactor.cachedb.starter;

public record RuntimeProfilePropertySnapshot(
        String property,
        String value,
        String unit,
        String description
) {
}

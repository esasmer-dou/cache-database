package com.reactor.cachedb.starter;

public record EffectiveTuningEntry(
        String group,
        String property,
        String value,
        String source,
        String description
) {
}

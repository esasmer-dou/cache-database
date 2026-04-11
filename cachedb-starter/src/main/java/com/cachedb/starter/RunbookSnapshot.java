package com.reactor.cachedb.starter;

public record RunbookSnapshot(
        String code,
        String title,
        String trigger,
        String firstAction,
        String reference
) {
}

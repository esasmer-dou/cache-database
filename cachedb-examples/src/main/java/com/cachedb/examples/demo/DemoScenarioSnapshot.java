package com.reactor.cachedb.examples.demo;

import java.time.Instant;

public record DemoScenarioSnapshot(
        boolean seeded,
        boolean running,
        String activeScenario,
        String activeScenarioLabel,
        String activeScenarioSummary,
        int activeReaderThreads,
        int activeWriterThreads,
        long customerCount,
        long productCount,
        long cartCount,
        long orderCount,
        long orderLineCount,
        long readCount,
        long writeCount,
        long errorCount,
        String seedSummary,
        String currentAction,
        long lastSeededAtEpochMillis,
        long lastStartedAtEpochMillis,
        long lastStoppedAtEpochMillis,
        String lastError,
        Instant recordedAt
) {
}

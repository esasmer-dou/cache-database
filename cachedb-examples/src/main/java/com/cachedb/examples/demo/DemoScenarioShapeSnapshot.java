package com.reactor.cachedb.examples.demo;

import java.util.List;

public record DemoScenarioShapeSnapshot(
        List<DemoScenarioShapeRecord> items,
        long recordedAtEpochMillis
) {
}

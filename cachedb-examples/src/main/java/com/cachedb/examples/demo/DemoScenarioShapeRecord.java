package com.reactor.cachedb.examples.demo;

public record DemoScenarioShapeRecord(
        String key,
        String mode,
        long sampleCount,
        long lastPrimaryCount,
        long averagePrimaryCount,
        long lastRelatedCount,
        long averageRelatedCount,
        long lastStepCount,
        long averageStepCount,
        long lastObjectCount,
        long averageObjectCount,
        long lastWriteCount,
        long averageWriteCount,
        long lastRecordedAtEpochMillis
) {
}

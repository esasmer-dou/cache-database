package com.reactor.cachedb.starter;

import java.time.Instant;
import java.util.List;

public record EffectiveTuningSnapshot(
        Instant capturedAt,
        int explicitOverrideCount,
        int entryCount,
        List<EffectiveTuningEntry> items
) {
}

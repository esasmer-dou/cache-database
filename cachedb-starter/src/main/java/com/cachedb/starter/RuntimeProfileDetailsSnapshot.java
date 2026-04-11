package com.reactor.cachedb.starter;

import java.util.List;

public record RuntimeProfileDetailsSnapshot(
        String activeProfile,
        String mode,
        boolean automaticSwitchingEnabled,
        String manualOverrideProfile,
        String lastObservedPressureLevel,
        long switchCount,
        long lastSwitchedAtEpochMillis,
        List<RuntimeProfilePropertySnapshot> properties
) {
}

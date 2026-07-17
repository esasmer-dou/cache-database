package com.reactor.cachedb.spring.boot;

import java.time.Duration;

record CacheScheduledWarmDefinition(
        String jobName,
        ScheduleKind scheduleKind,
        String cron,
        String zone,
        Duration interval,
        Duration initialDelay,
        CacheScheduledWarmMode mode,
        Duration lockAtMostFor,
        Duration lockWaitTimeout,
        Duration lockRetryInterval,
        Duration minimumInterval,
        boolean reconcileHotSet,
        int reconcileMaxRowsPerRun,
        int reconcileScanCount
) {
    enum ScheduleKind {
        CRON,
        FIXED_DELAY,
        FIXED_RATE
    }
}

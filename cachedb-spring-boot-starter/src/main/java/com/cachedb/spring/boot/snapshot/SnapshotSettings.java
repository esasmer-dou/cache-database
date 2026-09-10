package com.reactor.cachedb.spring.boot.snapshot;

import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

/** Per-plan configuration under cachedb.snapshots.jobs.<name>. */
public record SnapshotSettings(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT1M") Duration interval,
        @DefaultValue("PT3M") Duration warnAge,
        @DefaultValue("PT30M") Duration maxAge,
        @DefaultValue("PT1H") Duration retention,
        @DefaultValue("PT110S") Duration timeout,
        @DefaultValue("PT90S") Duration preparationTimeout,
        @DefaultValue("PT2M") Duration leaseDuration,
        @DefaultValue("256") int batchRows,
        @DefaultValue("256") int fetchRows,
        @DefaultValue("300000") int maxSourceRows,
        @DefaultValue("100000") int maxRowsPerSource,
        @DefaultValue("64MB") DataSize maxSourceSize,
        @DefaultValue("4MB") DataSize batchTargetSize,
        @DefaultValue("1MB") DataSize payloadWarningSize,
        @DefaultValue("64MB") DataSize catalogWarningSize,
        @DefaultValue("") String spoolDirectory) {
    public SnapshotSettings {
        for (Duration d :
                new Duration[] {
                    interval, warnAge, maxAge, retention, timeout, preparationTimeout, leaseDuration
                })
            if (d == null
                    || d.isNegative()
                    || d.toMillis() < 1
                    || d.compareTo(Duration.ofDays(7)) > 0)
                throw new IllegalArgumentException(
                        "Snapshot durations must be positive and at most seven days");
        if (warnAge.compareTo(maxAge) >= 0
                || interval.plus(timeout).compareTo(maxAge) >= 0
                || preparationTimeout.compareTo(timeout) >= 0
                || retention.compareTo(maxAge.plus(timeout)) <= 0
                || leaseDuration.toMillis() < 3000)
            throw new IllegalArgumentException(
                    "Snapshot interval/timeout/freshness/retention are inconsistent");
        if (batchRows < 1
                || batchRows > 10000
                || fetchRows < 1
                || fetchRows > 10000
                || maxSourceRows < 1
                || maxSourceRows == Integer.MAX_VALUE
                || maxRowsPerSource < 1
                || maxSourceSize == null
                || maxSourceSize.toBytes() < 1
                || batchTargetSize == null
                || batchTargetSize.toBytes() < 1
                || payloadWarningSize == null
                || payloadWarningSize.toBytes() < 0
                || catalogWarningSize == null
                || catalogWarningSize.toBytes() < 0
                || spoolDirectory == null)
            throw new IllegalArgumentException("Invalid snapshot resource settings");
    }

    public static SnapshotSettings defaults() {
        return new SnapshotSettings(
                true,
                Duration.ofMinutes(1),
                Duration.ofMinutes(3),
                Duration.ofMinutes(30),
                Duration.ofHours(1),
                Duration.ofSeconds(110),
                Duration.ofSeconds(90),
                Duration.ofMinutes(2),
                256,
                256,
                300000,
                100000,
                DataSize.ofMegabytes(64),
                DataSize.ofMegabytes(4),
                DataSize.ofMegabytes(1),
                DataSize.ofMegabytes(64),
                "");
    }

    public Path directory() {
        return Path.of(
                spoolDirectory.isBlank() ? System.getProperty("java.io.tmpdir") : spoolDirectory);
    }
}

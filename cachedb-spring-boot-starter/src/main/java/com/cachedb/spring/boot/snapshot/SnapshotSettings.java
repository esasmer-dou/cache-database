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
        @DefaultValue("") String spoolDirectory,
        @DefaultValue("PT0S") Duration dirtyFlushInterval,
        @DefaultValue("0") int dirtyFlushThreshold) {
    public SnapshotSettings(boolean enabled, Duration interval, Duration warnAge, Duration maxAge, Duration retention,
            Duration timeout, Duration preparationTimeout, Duration leaseDuration, int batchRows, int fetchRows,
            int maxSourceRows, int maxRowsPerSource, DataSize maxSourceSize, DataSize batchTargetSize,
            DataSize payloadWarningSize, DataSize catalogWarningSize, String spoolDirectory) {
        this(enabled, interval, warnAge, maxAge, retention, timeout, preparationTimeout, leaseDuration, batchRows,
                fetchRows, maxSourceRows, maxRowsPerSource, maxSourceSize, batchTargetSize, payloadWarningSize,
                catalogWarningSize, spoolDirectory, Duration.ZERO, 0);
    }

    public SnapshotSettings withDirtyFlush(Duration interval, int threshold) {
        return new SnapshotSettings(enabled, this.interval, warnAge, maxAge, retention, timeout, preparationTimeout,
                leaseDuration, batchRows, fetchRows, maxSourceRows, maxRowsPerSource, maxSourceSize, batchTargetSize,
                payloadWarningSize, catalogWarningSize, spoolDirectory, interval, threshold);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public SnapshotSettings {
        if (dirtyFlushInterval == null || dirtyFlushInterval.isNegative()
                || (!dirtyFlushInterval.isZero() && dirtyFlushInterval.toMillis() < 250)
                || dirtyFlushInterval.compareTo(Duration.ofDays(1)) > 0
                || dirtyFlushThreshold < 0 || dirtyFlushThreshold > batchRows)
            throw new IllegalArgumentException("Dirty flush interval must be disabled or 250ms..1d; threshold must be 0..batchRows");
        for (Duration d :
                new Duration[] {
                    interval, maxAge, retention, timeout, preparationTimeout, leaseDuration
                })
            if (d == null
                    || d.isNegative()
                    || d.toMillis() < 1
                    || d.compareTo(Duration.ofDays(7)) > 0)
                throw new IllegalArgumentException(
                        "Snapshot durations must be positive and at most seven days");
        if (warnAge == null || warnAge.isNegative() || warnAge.compareTo(maxAge) >= 0
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

package com.reactor.cachedb.core.readmodel;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** Explicit identity and lifetime limits for a precomputed COUNT/FACET read model. */
public record CountFacetDefinition(String name, Duration maxAge, Duration retention, Duration refreshLease) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    private static final Duration MAX_RETENTION = Duration.ofDays(7);
    private static final Duration MAX_REFRESH_LEASE = Duration.ofMinutes(5);

    public CountFacetDefinition {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches())
            throw new IllegalArgumentException("Invalid count/facet read model name");
        requireMillis(maxAge, "maxAge");
        requireMillis(retention, "retention");
        requireMillis(refreshLease, "refreshLease");
        if (maxAge.compareTo(retention) > 0 || retention.compareTo(MAX_RETENTION) > 0)
            throw new IllegalArgumentException("Require maxAge <= retention <= 7 days");
        if (refreshLease.compareTo(MAX_REFRESH_LEASE) > 0)
            throw new IllegalArgumentException("refreshLease must not exceed 5 minutes");
    }

    private static void requireMillis(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero() || value.getNano() % 1_000_000 != 0)
            throw new IllegalArgumentException(name + " must be positive whole milliseconds");
    }
}

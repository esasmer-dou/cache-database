package com.reactor.cachedb.core.config;

/** Coalescing is local to one repository/JVM; JDBC still enforces its own timeout. */
public record SingleFlightConfig(boolean enabled, int maxInFlightKeys, int maxWaiters, long timeoutMillis,
        int maxResultCharacters) {
    public SingleFlightConfig(boolean enabled, int maxInFlightKeys, int maxWaiters, long timeoutMillis) {
        this(enabled, maxInFlightKeys, maxWaiters, timeoutMillis, 16 * 1024 * 1024);
    }
    public SingleFlightConfig {
        if (maxInFlightKeys < 1 || maxInFlightKeys > 65_536 || maxWaiters < 1 || maxWaiters > 65_536
                || timeoutMillis < 1 || timeoutMillis > 300_000 || maxResultCharacters < 1
                || maxResultCharacters > 64 * 1024 * 1024)
            throw new IllegalArgumentException("Invalid bounded single-flight configuration");
    }

    public static SingleFlightConfig defaults() { return new SingleFlightConfig(true, 4096, 4096, 30_000); }
}

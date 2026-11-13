package com.reactor.cachedb.core.config;

import java.util.Objects;

/** Independent runtime options: does not change NearCachePolicy's binary/record shape. */
public record NearCacheInvalidationConfig(
        NearCacheInvalidationMode mode, boolean publishLegacyInvalidations,
        int maxMasters, int maxNodes, int maxNamespaces,
        int ioTimeoutMillis, int heartbeatMillis, int healthTimeoutMillis,
        int maxAttempts, int initialBackoffMillis, int maxBackoffMillis, int retryCooldownMillis) {
    public NearCacheInvalidationConfig {
        Objects.requireNonNull(mode, "mode");
        if (!publishLegacyInvalidations && mode != NearCacheInvalidationMode.SERVER_TRACKING)
            throw new IllegalArgumentException(
                    "Disabling legacy publication requires SERVER_TRACKING and an all-consumer upgrade contract");
        if (maxMasters < 1 || maxMasters > 16 || maxNodes < maxMasters || maxNodes > 64
                || maxNamespaces < 1 || maxNamespaces > 256
                || ioTimeoutMillis < 50 || ioTimeoutMillis > 2000
                || heartbeatMillis < 25 || heartbeatMillis > 1000 || heartbeatMillis * 2 > ioTimeoutMillis
                || healthTimeoutMillis < ioTimeoutMillis + heartbeatMillis || healthTimeoutMillis > 10000
                || maxAttempts < 1 || maxAttempts > 8
                || initialBackoffMillis < 100 || initialBackoffMillis > 1000
                || maxBackoffMillis < initialBackoffMillis || maxBackoffMillis > 5000
                || retryCooldownMillis < 1000 || retryCooldownMillis > 60000)
            throw new IllegalArgumentException("Invalid bounded near-cache invalidation configuration");
    }
    public static NearCacheInvalidationConfig defaults() {
        return new NearCacheInvalidationConfig(NearCacheInvalidationMode.AUTO, true,
                16, 64, 256, 500, 100, 1000, 5, 100, 5000, 30000);
    }
    public NearCacheInvalidationConfig withMode(NearCacheInvalidationMode mode) {
        return new NearCacheInvalidationConfig(mode, publishLegacyInvalidations, maxMasters, maxNodes,
                maxNamespaces, ioTimeoutMillis, heartbeatMillis, healthTimeoutMillis,
                maxAttempts, initialBackoffMillis, maxBackoffMillis, retryCooldownMillis);
    }

    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() {
        return new Builder()
                .mode(mode)
                .publishLegacyInvalidations(publishLegacyInvalidations)
                .maxMasters(maxMasters)
                .maxNodes(maxNodes)
                .maxNamespaces(maxNamespaces)
                .ioTimeoutMillis(ioTimeoutMillis)
                .heartbeatMillis(heartbeatMillis)
                .healthTimeoutMillis(healthTimeoutMillis)
                .maxAttempts(maxAttempts)
                .initialBackoffMillis(initialBackoffMillis)
                .maxBackoffMillis(maxBackoffMillis)
                .retryCooldownMillis(retryCooldownMillis);
    }
    public static final class Builder {
        private final NearCacheInvalidationConfig defaults = NearCacheInvalidationConfig.defaults();
        private NearCacheInvalidationMode mode = defaults.mode();
        public Builder mode(NearCacheInvalidationMode value) { mode = value; return this; }
        private boolean publishLegacyInvalidations = defaults.publishLegacyInvalidations();
        public Builder publishLegacyInvalidations(boolean value) { publishLegacyInvalidations = value; return this; }
        private int maxMasters = defaults.maxMasters();
        public Builder maxMasters(int value) { maxMasters = value; return this; }
        private int maxNodes = defaults.maxNodes();
        public Builder maxNodes(int value) { maxNodes = value; return this; }
        private int maxNamespaces = defaults.maxNamespaces();
        public Builder maxNamespaces(int value) { maxNamespaces = value; return this; }
        private int ioTimeoutMillis = defaults.ioTimeoutMillis();
        public Builder ioTimeoutMillis(int value) { ioTimeoutMillis = value; return this; }
        private int heartbeatMillis = defaults.heartbeatMillis();
        public Builder heartbeatMillis(int value) { heartbeatMillis = value; return this; }
        private int healthTimeoutMillis = defaults.healthTimeoutMillis();
        public Builder healthTimeoutMillis(int value) { healthTimeoutMillis = value; return this; }
        private int maxAttempts = defaults.maxAttempts();
        public Builder maxAttempts(int value) { maxAttempts = value; return this; }
        private int initialBackoffMillis = defaults.initialBackoffMillis();
        public Builder initialBackoffMillis(int value) { initialBackoffMillis = value; return this; }
        private int maxBackoffMillis = defaults.maxBackoffMillis();
        public Builder maxBackoffMillis(int value) { maxBackoffMillis = value; return this; }
        private int retryCooldownMillis = defaults.retryCooldownMillis();
        public Builder retryCooldownMillis(int value) { retryCooldownMillis = value; return this; }
        public NearCacheInvalidationConfig build() {
            return new NearCacheInvalidationConfig(mode, publishLegacyInvalidations, maxMasters, maxNodes,
                    maxNamespaces, ioTimeoutMillis, heartbeatMillis, healthTimeoutMillis,
                    maxAttempts, initialBackoffMillis, maxBackoffMillis, retryCooldownMillis);
        }
    }
}

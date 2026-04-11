package com.reactor.cachedb.core.config;

public record IncidentQueueConfig(
        boolean enabled,
        String streamKey,
        long maxLength,
        int maxRetries,
        long retryBackoffMillis
) {
    public static Builder builder() {
        return new Builder();
    }

    public static IncidentQueueConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled;
        private String streamKey = "cachedb:stream:admin:incident-queue";
        private long maxLength = 2_000;
        private int maxRetries = 1;
        private long retryBackoffMillis = 100;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder streamKey(String streamKey) {
            this.streamKey = streamKey;
            return this;
        }

        public Builder maxLength(long maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryBackoffMillis(long retryBackoffMillis) {
            this.retryBackoffMillis = retryBackoffMillis;
            return this;
        }

        public IncidentQueueConfig build() {
            return new IncidentQueueConfig(enabled, streamKey, maxLength, maxRetries, retryBackoffMillis);
        }
    }
}

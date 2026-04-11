package com.reactor.cachedb.core.config;

public record IncidentWebhookConfig(
        boolean enabled,
        String endpointUrl,
        long connectTimeoutMillis,
        long requestTimeoutMillis,
        int workerThreads,
        int queueCapacity,
        int maxRetries,
        long retryBackoffMillis,
        String headerName,
        String headerValue
) {
    public static Builder builder() {
        return new Builder();
    }

    public static IncidentWebhookConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled;
        private String endpointUrl = "";
        private long connectTimeoutMillis = 2_000;
        private long requestTimeoutMillis = 5_000;
        private int workerThreads = 1;
        private int queueCapacity = 256;
        private int maxRetries = 2;
        private long retryBackoffMillis = 500;
        private String headerName = "";
        private String headerValue = "";

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder endpointUrl(String endpointUrl) {
            this.endpointUrl = endpointUrl;
            return this;
        }

        public Builder connectTimeoutMillis(long connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        public Builder requestTimeoutMillis(long requestTimeoutMillis) {
            this.requestTimeoutMillis = requestTimeoutMillis;
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
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

        public Builder headerName(String headerName) {
            this.headerName = headerName;
            return this;
        }

        public Builder headerValue(String headerValue) {
            this.headerValue = headerValue;
            return this;
        }

        public IncidentWebhookConfig build() {
            return new IncidentWebhookConfig(
                    enabled,
                    endpointUrl,
                    connectTimeoutMillis,
                    requestTimeoutMillis,
                    workerThreads,
                    queueCapacity,
                    maxRetries,
                    retryBackoffMillis,
                    headerName,
                    headerValue
            );
        }
    }
}

package com.reactor.cachedb.core.config;

public record AdminHttpConfig(
        boolean enabled,
        String host,
        int port,
        int backlog,
        int workerThreads,
        boolean dashboardEnabled,
        boolean corsEnabled,
        String dashboardTitle,
        boolean authEnabled,
        String authToken,
        String authHeaderName,
        int requestQueueCapacity,
        int backgroundWorkerThreads,
        int backgroundQueueCapacity,
        int maxRequestBodyBytes,
        int jobStatusTtlSeconds
) {
    public AdminHttpConfig(
            boolean enabled,
            String host,
            int port,
            int backlog,
            int workerThreads,
            boolean dashboardEnabled,
            boolean corsEnabled,
            String dashboardTitle,
            boolean authEnabled,
            String authToken,
            String authHeaderName
    ) {
        this(
                enabled, host, port, backlog, workerThreads, dashboardEnabled, corsEnabled,
                dashboardTitle, authEnabled, authToken, authHeaderName,
                128, 2, 32, 1_048_576, 86_400
        );
    }

    public AdminHttpConfig {
        host = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        dashboardTitle = dashboardTitle == null || dashboardTitle.isBlank() ? "CacheDB Admin" : dashboardTitle.trim();
        authToken = authToken == null ? "" : authToken.trim();
        authHeaderName = authHeaderName == null || authHeaderName.isBlank() ? "Authorization" : authHeaderName.trim();
        if (authEnabled && authToken.isBlank()) {
            throw new IllegalArgumentException("Admin HTTP auth is enabled but authToken is blank.");
        }
        if (workerThreads <= 0 || requestQueueCapacity <= 0 || backgroundWorkerThreads <= 0
                || backgroundQueueCapacity <= 0 || maxRequestBodyBytes <= 0 || jobStatusTtlSeconds <= 0) {
            throw new IllegalArgumentException("Admin HTTP worker, queue, body, and job TTL limits must be greater than zero.");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static AdminHttpConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled;
        private String host = "127.0.0.1";
        private int port;
        private int backlog = 64;
        private int workerThreads = 2;
        private boolean dashboardEnabled = true;
        private boolean corsEnabled;
        private String dashboardTitle = "CacheDB Admin";
        private boolean authEnabled;
        private String authToken = "";
        private String authHeaderName = "Authorization";
        private int requestQueueCapacity = 128;
        private int backgroundWorkerThreads = 2;
        private int backgroundQueueCapacity = 32;
        private int maxRequestBodyBytes = 1_048_576;
        private int jobStatusTtlSeconds = 86_400;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder backlog(int backlog) {
            this.backlog = backlog;
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        public Builder dashboardEnabled(boolean dashboardEnabled) {
            this.dashboardEnabled = dashboardEnabled;
            return this;
        }

        public Builder corsEnabled(boolean corsEnabled) {
            this.corsEnabled = corsEnabled;
            return this;
        }

        public Builder dashboardTitle(String dashboardTitle) {
            this.dashboardTitle = dashboardTitle;
            return this;
        }

        public Builder authEnabled(boolean authEnabled) {
            this.authEnabled = authEnabled;
            return this;
        }

        public Builder authToken(String authToken) {
            this.authToken = authToken;
            return this;
        }

        public Builder authHeaderName(String authHeaderName) {
            this.authHeaderName = authHeaderName;
            return this;
        }

        public Builder requestQueueCapacity(int requestQueueCapacity) {
            this.requestQueueCapacity = requestQueueCapacity;
            return this;
        }

        public Builder backgroundWorkerThreads(int backgroundWorkerThreads) {
            this.backgroundWorkerThreads = backgroundWorkerThreads;
            return this;
        }

        public Builder backgroundQueueCapacity(int backgroundQueueCapacity) {
            this.backgroundQueueCapacity = backgroundQueueCapacity;
            return this;
        }

        public Builder maxRequestBodyBytes(int maxRequestBodyBytes) {
            this.maxRequestBodyBytes = maxRequestBodyBytes;
            return this;
        }

        public Builder jobStatusTtlSeconds(int jobStatusTtlSeconds) {
            this.jobStatusTtlSeconds = jobStatusTtlSeconds;
            return this;
        }

        public AdminHttpConfig build() {
            return new AdminHttpConfig(
                    enabled,
                    host,
                    port,
                    backlog,
                    workerThreads,
                    dashboardEnabled,
                    corsEnabled,
                    dashboardTitle,
                    authEnabled,
                    authToken,
                    authHeaderName,
                    requestQueueCapacity,
                    backgroundWorkerThreads,
                    backgroundQueueCapacity,
                    maxRequestBodyBytes,
                    jobStatusTtlSeconds
            );
        }
    }
}

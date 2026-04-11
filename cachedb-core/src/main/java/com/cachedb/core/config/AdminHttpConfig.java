package com.reactor.cachedb.core.config;

public record AdminHttpConfig(
        boolean enabled,
        String host,
        int port,
        int backlog,
        int workerThreads,
        boolean dashboardEnabled,
        boolean corsEnabled,
        String dashboardTitle
) {
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

        public AdminHttpConfig build() {
            return new AdminHttpConfig(
                    enabled,
                    host,
                    port,
                    backlog,
                    workerThreads,
                    dashboardEnabled,
                    corsEnabled,
                    dashboardTitle
            );
        }
    }
}

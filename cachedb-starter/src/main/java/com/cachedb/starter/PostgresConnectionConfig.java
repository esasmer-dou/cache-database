package com.reactor.cachedb.starter;

import org.postgresql.ds.PGSimpleDataSource;

import java.util.Properties;

/**
 * @deprecated Use {@link com.reactor.cachedb.postgres.PostgresConnectionConfig}
 * from the explicit PostgreSQL provider.
 */
@Deprecated(forRemoval = false)
public record PostgresConnectionConfig(
        String jdbcUrl,
        String username,
        String password,
        int connectTimeoutSeconds,
        int socketTimeoutSeconds,
        boolean tcpKeepAlive,
        boolean rewriteBatchedInserts,
        int prepareThreshold,
        int defaultRowFetchSize,
        String applicationName,
        String additionalParameters
) {
    public static final String DEFAULT_PREFIX = com.reactor.cachedb.postgres.PostgresConnectionConfig.DEFAULT_PREFIX;

    public PGSimpleDataSource createDataSource() {
        return delegate().createDataSource();
    }

    public String normalizedJdbcUrl() {
        return delegate().normalizedJdbcUrl();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static PostgresConnectionConfig defaults() {
        return from(com.reactor.cachedb.postgres.PostgresConnectionConfig.defaults());
    }

    public static PostgresConnectionConfig fromSystemProperties(
            String prefix,
            String defaultJdbcUrl,
            String defaultUsername,
            String defaultPassword
    ) {
        return from(com.reactor.cachedb.postgres.PostgresConnectionConfig.fromSystemProperties(
                prefix,
                defaultJdbcUrl,
                defaultUsername,
                defaultPassword
        ));
    }

    public static PostgresConnectionConfig fromProperties(
            Properties properties,
            String prefix,
            String defaultJdbcUrl,
            String defaultUsername,
            String defaultPassword
    ) {
        return from(com.reactor.cachedb.postgres.PostgresConnectionConfig.fromProperties(
                properties,
                prefix,
                defaultJdbcUrl,
                defaultUsername,
                defaultPassword
        ));
    }

    private com.reactor.cachedb.postgres.PostgresConnectionConfig delegate() {
        return new com.reactor.cachedb.postgres.PostgresConnectionConfig(
                jdbcUrl,
                username,
                password,
                connectTimeoutSeconds,
                socketTimeoutSeconds,
                tcpKeepAlive,
                rewriteBatchedInserts,
                prepareThreshold,
                defaultRowFetchSize,
                applicationName,
                additionalParameters
        );
    }

    private static PostgresConnectionConfig from(com.reactor.cachedb.postgres.PostgresConnectionConfig config) {
        return new PostgresConnectionConfig(
                config.jdbcUrl(),
                config.username(),
                config.password(),
                config.connectTimeoutSeconds(),
                config.socketTimeoutSeconds(),
                config.tcpKeepAlive(),
                config.rewriteBatchedInserts(),
                config.prepareThreshold(),
                config.defaultRowFetchSize(),
                config.applicationName(),
                config.additionalParameters()
        );
    }

    public static final class Builder {
        private final com.reactor.cachedb.postgres.PostgresConnectionConfig.Builder delegate =
                com.reactor.cachedb.postgres.PostgresConnectionConfig.builder();

        public Builder jdbcUrl(String jdbcUrl) {
            delegate.jdbcUrl(jdbcUrl);
            return this;
        }

        public Builder username(String username) {
            delegate.username(username);
            return this;
        }

        public Builder password(String password) {
            delegate.password(password);
            return this;
        }

        public Builder connectTimeoutSeconds(int connectTimeoutSeconds) {
            delegate.connectTimeoutSeconds(connectTimeoutSeconds);
            return this;
        }

        public Builder socketTimeoutSeconds(int socketTimeoutSeconds) {
            delegate.socketTimeoutSeconds(socketTimeoutSeconds);
            return this;
        }

        public Builder tcpKeepAlive(boolean tcpKeepAlive) {
            delegate.tcpKeepAlive(tcpKeepAlive);
            return this;
        }

        public Builder rewriteBatchedInserts(boolean rewriteBatchedInserts) {
            delegate.rewriteBatchedInserts(rewriteBatchedInserts);
            return this;
        }

        public Builder prepareThreshold(int prepareThreshold) {
            delegate.prepareThreshold(prepareThreshold);
            return this;
        }

        public Builder defaultRowFetchSize(int defaultRowFetchSize) {
            delegate.defaultRowFetchSize(defaultRowFetchSize);
            return this;
        }

        public Builder applicationName(String applicationName) {
            delegate.applicationName(applicationName);
            return this;
        }

        public Builder additionalParameters(String additionalParameters) {
            delegate.additionalParameters(additionalParameters);
            return this;
        }

        public PostgresConnectionConfig build() {
            return from(delegate.build());
        }
    }
}

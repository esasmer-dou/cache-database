package com.reactor.cachedb.starter;

import org.postgresql.ds.PGSimpleDataSource;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.StringJoiner;

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
    public static final String DEFAULT_PREFIX = "cachedb.postgres";

    public PGSimpleDataSource createDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(normalizedJdbcUrl());
        dataSource.setUser(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    public String normalizedJdbcUrl() {
        Map<String, String> parameters = parseParameters(jdbcUrl);
        parameters.putIfAbsent("connectTimeout", String.valueOf(connectTimeoutSeconds));
        parameters.putIfAbsent("socketTimeout", String.valueOf(socketTimeoutSeconds));
        parameters.putIfAbsent("tcpKeepAlive", String.valueOf(tcpKeepAlive));
        parameters.putIfAbsent("reWriteBatchedInserts", String.valueOf(rewriteBatchedInserts));
        parameters.putIfAbsent("prepareThreshold", String.valueOf(prepareThreshold));
        parameters.putIfAbsent("defaultRowFetchSize", String.valueOf(defaultRowFetchSize));
        if (applicationName != null && !applicationName.isBlank()) {
            parameters.putIfAbsent("ApplicationName", applicationName.trim());
        }
        if (additionalParameters != null && !additionalParameters.isBlank()) {
            parameters.putAll(parseLooseParameters(additionalParameters));
        }
        return baseUrl(jdbcUrl) + toQueryString(parameters);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static PostgresConnectionConfig defaults() {
        return builder().build();
    }

    public static PostgresConnectionConfig fromSystemProperties(
            String prefix,
            String defaultJdbcUrl,
            String defaultUsername,
            String defaultPassword
    ) {
        return fromProperties(System.getProperties(), prefix, defaultJdbcUrl, defaultUsername, defaultPassword);
    }

    public static PostgresConnectionConfig fromProperties(
            Properties properties,
            String prefix,
            String defaultJdbcUrl,
            String defaultUsername,
            String defaultPassword
    ) {
        String normalizedPrefix = normalizePrefix(prefix);
        return PostgresConnectionConfig.builder()
                .jdbcUrl(string(properties, normalizedPrefix + ".jdbcUrl", string(properties, normalizedPrefix + "Url", defaultJdbcUrl)))
                .username(string(properties, normalizedPrefix + ".user", string(properties, normalizedPrefix + "User", defaultUsername)))
                .password(string(properties, normalizedPrefix + ".password", string(properties, normalizedPrefix + "Password", defaultPassword)))
                .connectTimeoutSeconds(integer(properties, normalizedPrefix + ".connectTimeoutSeconds", 30))
                .socketTimeoutSeconds(integer(properties, normalizedPrefix + ".socketTimeoutSeconds", 300))
                .tcpKeepAlive(bool(properties, normalizedPrefix + ".tcpKeepAlive", true))
                .rewriteBatchedInserts(bool(properties, normalizedPrefix + ".rewriteBatchedInserts", true))
                .prepareThreshold(integer(properties, normalizedPrefix + ".prepareThreshold", 5))
                .defaultRowFetchSize(integer(properties, normalizedPrefix + ".defaultRowFetchSize", 0))
                .applicationName(string(properties, normalizedPrefix + ".applicationName", "cache-database"))
                .additionalParameters(string(properties, normalizedPrefix + ".additionalParameters", ""))
                .build();
    }

    public static final class Builder {
        private String jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/postgres";
        private String username = "postgres";
        private String password = "postgresql";
        private int connectTimeoutSeconds = 30;
        private int socketTimeoutSeconds = 300;
        private boolean tcpKeepAlive = true;
        private boolean rewriteBatchedInserts = true;
        private int prepareThreshold = 5;
        private int defaultRowFetchSize = 0;
        private String applicationName = "cache-database";
        private String additionalParameters = "";

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder connectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
            return this;
        }

        public Builder socketTimeoutSeconds(int socketTimeoutSeconds) {
            this.socketTimeoutSeconds = socketTimeoutSeconds;
            return this;
        }

        public Builder tcpKeepAlive(boolean tcpKeepAlive) {
            this.tcpKeepAlive = tcpKeepAlive;
            return this;
        }

        public Builder rewriteBatchedInserts(boolean rewriteBatchedInserts) {
            this.rewriteBatchedInserts = rewriteBatchedInserts;
            return this;
        }

        public Builder prepareThreshold(int prepareThreshold) {
            this.prepareThreshold = prepareThreshold;
            return this;
        }

        public Builder defaultRowFetchSize(int defaultRowFetchSize) {
            this.defaultRowFetchSize = defaultRowFetchSize;
            return this;
        }

        public Builder applicationName(String applicationName) {
            this.applicationName = applicationName;
            return this;
        }

        public Builder additionalParameters(String additionalParameters) {
            this.additionalParameters = additionalParameters;
            return this;
        }

        public PostgresConnectionConfig build() {
            return new PostgresConnectionConfig(
                    Objects.requireNonNull(jdbcUrl, "jdbcUrl"),
                    Objects.requireNonNull(username, "username"),
                    Objects.requireNonNull(password, "password"),
                    connectTimeoutSeconds,
                    socketTimeoutSeconds,
                    tcpKeepAlive,
                    rewriteBatchedInserts,
                    prepareThreshold,
                    defaultRowFetchSize,
                    applicationName,
                    additionalParameters == null ? "" : additionalParameters.trim()
            );
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return DEFAULT_PREFIX;
        }
        return prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    private static Map<String, String> parseParameters(String jdbcUrl) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        int separator = jdbcUrl.indexOf('?');
        if (separator < 0 || separator + 1 >= jdbcUrl.length()) {
            return values;
        }
        values.putAll(parseLooseParameters(jdbcUrl.substring(separator + 1).replace('&', ';')));
        return values;
    }

    private static Map<String, String> parseLooseParameters(String rawParameters) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String token : rawParameters.split(";")) {
            if (token == null) {
                continue;
            }
            String normalized = token.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            String[] parts = normalized.split("=", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                values.put(parts[0].trim(), parts[1].trim());
            }
        }
        return values;
    }

    private static String baseUrl(String jdbcUrl) {
        int separator = jdbcUrl.indexOf('?');
        return separator < 0 ? jdbcUrl : jdbcUrl.substring(0, separator);
    }

    private static String toQueryString(Map<String, String> parameters) {
        if (parameters.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("&", "?", "");
        parameters.forEach((key, value) -> joiner.add(key + "=" + value));
        return joiner.toString();
    }

    private static String string(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int integer(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    private static boolean bool(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean '" + value + "' for " + key);
        };
    }
}

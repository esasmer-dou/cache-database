package com.reactor.cachedb.prodtest.scenario;

import org.postgresql.ds.PGSimpleDataSource;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;

final class ProductionTestEnvironment {

    private static final String DEFAULT_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:5432/postgres";
    private static final String DEFAULT_POSTGRES_USER = "postgres";
    private static final String DEFAULT_POSTGRES_PASSWORD = "postgresql";
    private static final String DEFAULT_REDIS_PASSWORD = "welcome1";
    private static final int DEFAULT_POSTGRES_CONNECT_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_POSTGRES_SOCKET_TIMEOUT_SECONDS = 15;
    private static final int DEFAULT_REDIS_TIMEOUT_MILLIS = 10_000;

    private ProductionTestEnvironment() {
    }

    static String postgresUrl() {
        return property("cachedb.prod.postgres.url", "cachedb.it.postgres.url", DEFAULT_POSTGRES_URL);
    }

    static String postgresUser() {
        return property("cachedb.prod.postgres.user", "cachedb.it.postgres.user", DEFAULT_POSTGRES_USER);
    }

    static String postgresPassword() {
        return property("cachedb.prod.postgres.password", "cachedb.it.postgres.password", DEFAULT_POSTGRES_PASSWORD);
    }

    static PGSimpleDataSource postgresDataSource() {
        return postgresDataSource(postgresUrl());
    }

    static PGSimpleDataSource postgresDataSource(String url) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(postgresUser());
        dataSource.setPassword(postgresPassword());
        dataSource.setConnectTimeout(intProperty(
                "cachedb.prod.postgres.connectTimeoutSeconds",
                DEFAULT_POSTGRES_CONNECT_TIMEOUT_SECONDS
        ));
        dataSource.setSocketTimeout(intProperty(
                "cachedb.prod.postgres.socketTimeoutSeconds",
                DEFAULT_POSTGRES_SOCKET_TIMEOUT_SECONDS
        ));
        dataSource.setTcpKeepAlive(true);
        dataSource.setApplicationName("cachedb-production-tests");
        return dataSource;
    }

    static Connection postgresConnection() throws SQLException {
        return postgresDataSource().getConnection();
    }

    // Destructive fixture reset only, after its business table has been dropped.
    // Never use this as a production tombstone retention policy.
    static void resetFixtureWriteFence(Connection connection, String table) throws SQLException {
        if (!table.matches("cachedb_prodtest_[a-z_]+")) throw new IllegalArgumentException("Not a production-test fixture table");
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT to_regclass('cachedb_write_fence')")) {
            result.next();
            if (result.getString(1) == null) return;
        }
        String schema = connection.getSchema();
        String scope = schema.length() + ":" + schema + table;
        String prefix = (scope.length() + ":" + scope).replace("!", "!!").replace("_", "!_").replace("%", "!%");
        try (var statement = connection.prepareStatement("DELETE FROM cachedb_write_fence WHERE identity_value LIKE ? ESCAPE '!'")) {
            statement.setString(1, prefix + "%");
            statement.executeUpdate();
        }
    }

    static String redisPassword() {
        return property("cachedb.prod.redis.password", "cachedb.it.redis.password", DEFAULT_REDIS_PASSWORD);
    }

    static String redisUri() {
        String configured = property("cachedb.prod.redis.uri", "cachedb.it.redis.uri", "");
        if (!configured.isEmpty()) {
            return configured;
        }
        return "redis://default:" + redisPassword() + "@127.0.0.1:6379";
    }

    static JedisPooled redisClient() {
        int timeoutMillis = intProperty("cachedb.prod.redis.timeoutMillis", DEFAULT_REDIS_TIMEOUT_MILLIS);
        return new JedisPooled(URI.create(redisUri()), timeoutMillis);
    }

    private static String property(String primaryName, String sharedName, String defaultValue) {
        String primary = normalizedProperty(primaryName);
        if (!primary.isEmpty()) {
            return primary;
        }
        String shared = normalizedProperty(sharedName);
        return shared.isEmpty() ? defaultValue : shared;
    }

    private static String normalizedProperty(String name) {
        return System.getProperty(name, "").trim();
    }

    private static int intProperty(String name, int defaultValue) {
        int value = Integer.getInteger(name, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }
}

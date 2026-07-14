package com.reactor.cachedb.prodtest.scenario;

final class ProductionTestEnvironment {

    private static final String DEFAULT_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:5432/postgres";
    private static final String DEFAULT_POSTGRES_USER = "postgres";
    private static final String DEFAULT_POSTGRES_PASSWORD = "postgresql";
    private static final String DEFAULT_REDIS_PASSWORD = "welcome1";

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
}

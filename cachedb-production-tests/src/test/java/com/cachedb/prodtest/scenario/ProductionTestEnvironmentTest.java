package com.reactor.cachedb.prodtest.scenario;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductionTestEnvironmentTest {

    private static final Set<String> PROPERTY_NAMES = Set.of(
            "cachedb.prod.postgres.url",
            "cachedb.it.postgres.url",
            "cachedb.prod.postgres.user",
            "cachedb.it.postgres.user",
            "cachedb.prod.postgres.password",
            "cachedb.it.postgres.password",
            "cachedb.prod.redis.uri",
            "cachedb.it.redis.uri",
            "cachedb.prod.redis.password",
            "cachedb.it.redis.password"
    );
    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void captureProperties() {
        PROPERTY_NAMES.forEach(name -> originalProperties.put(name, System.getProperty(name)));
    }

    @AfterEach
    void restoreProperties() {
        originalProperties.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
        originalProperties.clear();
    }

    @Test
    void productionPropertiesOverrideSharedIntegrationProperties() {
        System.setProperty("cachedb.it.postgres.url", "jdbc:postgresql://shared/db");
        System.setProperty("cachedb.prod.postgres.url", "jdbc:postgresql://production/db");
        System.setProperty("cachedb.it.redis.uri", "redis://shared:6379");
        System.setProperty("cachedb.prod.redis.uri", "redis://production:6379");

        assertEquals("jdbc:postgresql://production/db", ProductionTestEnvironment.postgresUrl());
        assertEquals("redis://production:6379", ProductionTestEnvironment.redisUri());
    }

    @Test
    void sharedIntegrationPropertiesAreUsedWhenProductionPropertiesAreAbsent() {
        System.setProperty("cachedb.it.postgres.url", "jdbc:postgresql://shared/db");
        System.setProperty("cachedb.it.postgres.user", "shared-user");
        System.setProperty("cachedb.it.postgres.password", "shared-password");
        System.setProperty("cachedb.it.redis.uri", "redis://shared:6379");

        assertEquals("jdbc:postgresql://shared/db", ProductionTestEnvironment.postgresUrl());
        assertEquals("shared-user", ProductionTestEnvironment.postgresUser());
        assertEquals("shared-password", ProductionTestEnvironment.postgresPassword());
        assertEquals("redis://shared:6379", ProductionTestEnvironment.redisUri());
    }

    @Test
    void blankPropertiesFallBackToDeterministicDefaults() {
        System.setProperty("cachedb.prod.postgres.url", "  ");
        System.setProperty("cachedb.it.postgres.url", "  ");
        System.setProperty("cachedb.prod.redis.uri", "  ");
        System.setProperty("cachedb.it.redis.uri", "  ");
        System.setProperty("cachedb.prod.redis.password", "  ");
        System.setProperty("cachedb.it.redis.password", "  ");

        assertEquals("jdbc:postgresql://127.0.0.1:5432/postgres", ProductionTestEnvironment.postgresUrl());
        assertEquals("redis://default:welcome1@127.0.0.1:6379", ProductionTestEnvironment.redisUri());
    }
}

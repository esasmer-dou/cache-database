package com.reactor.cachedb.starter;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SuppressWarnings("deprecation")
class PostgresCompatibilityApiTest {

    @Test
    void legacyConnectionConfigDelegatesToTheProviderImplementation() {
        PostgresConnectionConfig legacy = PostgresConnectionConfig.builder()
                .jdbcUrl("jdbc:postgresql://db.example/app")
                .username("app")
                .password("secret")
                .connectTimeoutSeconds(7)
                .socketTimeoutSeconds(19)
                .applicationName("compat-test")
                .build();
        com.reactor.cachedb.postgres.PostgresConnectionConfig provider =
                com.reactor.cachedb.postgres.PostgresConnectionConfig.builder()
                        .jdbcUrl("jdbc:postgresql://db.example/app")
                        .username("app")
                        .password("secret")
                        .connectTimeoutSeconds(7)
                        .socketTimeoutSeconds(19)
                        .applicationName("compat-test")
                        .build();

        assertEquals(provider.normalizedJdbcUrl(), legacy.normalizedJdbcUrl());
        assertEquals(provider.createDataSource().getURL(), legacy.createDataSource().getURL());
    }

    @Test
    void legacyBootstrapAndOutboxBuilderRemainUsableWithThePostgresProvider() {
        PGSimpleDataSource dataSource = assertInstanceOf(
                PGSimpleDataSource.class,
                CacheDatabaseBootstrapFactory.postgresDataSource(
                        "cachedb.compatibility.test",
                        "jdbc:postgresql://db.example/app",
                        "app",
                        "secret"
                )
        );
        PostgresOutboxExternalChangeFeedAdapter adapter =
                PostgresOutboxExternalChangeFeedAdapter.builder(dataSource)
                        .adapterName("compatibility-test")
                        .batchSize(10)
                        .build();

        assertEquals("app", dataSource.getUser());
        adapter.close();
    }
}

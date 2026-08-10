package com.reactor.cachedb.spring.boot.postgres;

import com.reactor.cachedb.jdbc.JdbcStorageProviders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresStarterProviderTest {
    @Test
    void contributesExactlyOneAutoSelectableProvider() {
        assertEquals("postgres", JdbcStorageProviders.requireSingle(getClass().getClassLoader()).id());
    }
}

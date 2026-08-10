package com.reactor.cachedb.spring.boot.mssql;

import com.reactor.cachedb.jdbc.JdbcStorageProviders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MssqlStarterProviderTest {
    @Test
    void contributesExactlyOneAutoSelectableProvider() {
        assertEquals("mssql", JdbcStorageProviders.requireSingle(getClass().getClassLoader()).id());
    }
}

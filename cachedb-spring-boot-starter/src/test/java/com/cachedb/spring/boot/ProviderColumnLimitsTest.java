package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.jdbc.JdbcDatabaseDialect;
import com.reactor.cachedb.mssql.MssqlDatabaseDialect;
import com.reactor.cachedb.oracle.OracleDatabaseDialect;
import com.reactor.cachedb.postgres.PostgresDatabaseDialect;
import java.lang.reflect.Proxy;
import java.sql.DatabaseMetaData;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderColumnLimitsTest {
    @Test void realProvidersUseTheirDefaultsOrTheBoundedActiveDictionary() throws Exception {
        List<JdbcDatabaseDialect> providers = List.of(new OracleDatabaseDialect(), new MssqlDatabaseDialect(), new PostgresDatabaseDialect());
        for (JdbcDatabaseDialect provider : providers) {
            int expected = provider instanceof OracleDatabaseDialect ? 1000 : provider instanceof MssqlDatabaseDialect ? 1024 : 1600;
            assertEquals(expected, provider.maxColumnsInTable());
            assertEquals(expected, provider.resolveMaxColumnsInTable(null));
            for (int reported : new int[]{0, -1, 1000, 1600, 4096, 32767}) {
                var metadata = (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                        (p, m, a) -> {
                            if (m.getName().equals("getMaxColumnsInTable")) return reported;
                            throw new AssertionError("Unexpected catalog access");
                        });
                assertEquals(reported <= 0 ? expected : Math.min(reported, 4096), provider.resolveMaxColumnsInTable(metadata));
            }
        }
    }
}

package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GeneratedInitialNativeFixtureDdlTest {
    @Test
    void tenantColumnUsesMssqlUnicodeWithoutChangingOtherProviderDdl(){
        String original="CREATE TABLE generated_initial_rows(id DECIMAL(19,0) NOT NULL PRIMARY KEY,"
                +"tenant_id VARCHAR(64) NOT NULL,secret VARCHAR(1024),entity_version DECIMAL(19,0) NOT NULL)";
        assertEquals(original.replace("tenant_id VARCHAR(64)","tenant_id NVARCHAR(64)"),
                GeneratedInitialNativeFixture.tableDdl(JdbcTenantPurgeDialect.MSSQL));
        assertEquals(original,GeneratedInitialNativeFixture.tableDdl(JdbcTenantPurgeDialect.POSTGRES));
        assertEquals(original,GeneratedInitialNativeFixture.tableDdl(JdbcTenantPurgeDialect.ORACLE));
    }
}

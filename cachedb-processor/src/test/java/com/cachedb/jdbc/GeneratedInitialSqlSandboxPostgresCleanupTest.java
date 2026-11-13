package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.initial.isolatedSql",matches="true")
@Timeout(60)
class GeneratedInitialSqlSandboxPostgresCleanupTest {
    @Test
    void actualSandboxCloseRemovesOnlyItsFreshPostgresSchema()throws Exception{
        try(var observer=observer();var sandbox=new GeneratedInitialSqlSandbox("postgres")){
            observer.setReadOnly(true);
            assertOwnedSchemaVisible(observer,sandbox);
            sandbox.close();
            assertRemoved(observer,sandbox);
        }
    }

    @Test
    void runtimeCloseFailureStillRemovesItsOwnedPostgresSchema()throws Exception{
        try(var observer=observer();var sandbox=new GeneratedInitialSqlSandbox("postgres")){
            observer.setReadOnly(true);
            assertOwnedSchemaVisible(observer,sandbox);
            var runtimeFailure=new IOException("Injected runtime fixture close failure");
            var runtimeCloses=new AtomicInteger();
            AutoCloseable runtime=()->{runtimeCloses.incrementAndGet();throw runtimeFailure;};

            var actual=assertThrows(IOException.class,
                    ()->GeneratedInitialSqlSandbox.closeFixture(false,sandbox,runtime));

            assertSame(runtimeFailure,actual,"Runtime close failure must remain primary");
            assertEquals(1,runtimeCloses.get());
            assertEquals(0,actual.getSuppressed().length,"Owned SQL cleanup must also succeed");
            assertRemoved(observer,sandbox);
        }
    }

    private static Connection observer()throws SQLException{
        var properties=new Properties();
        properties.setProperty("user",GeneratedInitialSqlSandbox.setting("postgres","USER",null));
        properties.setProperty("password",GeneratedInitialSqlSandbox.setting("postgres","PASSWORD",null));
        properties.setProperty("connectTimeout","5");
        properties.setProperty("socketTimeout","10");
        try{
            return DriverManager.getConnection(GeneratedInitialSqlSandbox.setting("postgres","URL",null),properties);
        }catch(SQLException failure){throw GeneratedInitialSqlSandbox.safe(failure);}
    }

    private static void assertOwnedSchemaVisible(Connection observer,GeneratedInitialSqlSandbox sandbox)throws SQLException{
        assertTrue(sandbox.created,"Sandbox must have successfully created its own schema");
        assertTrue(sandbox.name.matches("cachedb_v019_initial_[0-9a-f]{8}"));
        assertTrue(schemaExists(observer,sandbox.name),"Independent observer must see the newly created schema");
        try(var connection=sandbox.source.getConnection()){
            sandbox.assertScope(connection);
        }
    }

    private static void assertRemoved(Connection observer,GeneratedInitialSqlSandbox sandbox)throws SQLException{
        assertFalse(schemaExists(observer,sandbox.name),"Actual sandbox close must remove its exact owned schema");
        assertFalse(sandbox.created);
        assertTrue(sandbox.source.isClosed(),"Owned source pool must be closed");
        assertTrue(sandbox.admin.isClosed(),"Owned admin pool must be closed");
    }

    private static boolean schemaExists(Connection observer,String name)throws SQLException{
        try(var query=observer.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname=?)")){
            query.setQueryTimeout(5);query.setMaxRows(1);query.setString(1,name);
            try(var rows=query.executeQuery()){
                assertTrue(rows.next());
                boolean exists=rows.getBoolean(1);
                assertFalse(rows.next());
                return exists;
            }
        }catch(SQLException failure){throw GeneratedInitialSqlSandbox.safe(failure);}
    }
}

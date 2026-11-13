package com.example.cachedb.purge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

@Timeout(10)
class TenantPurgeOptInTest {
    private static final String RUN = "0123456789abcdef0123456789abcdef";

    @Test void missingOptInStopsBeforeDataSourceAndSqlInitialization() throws Exception {
        assertRejected(new MockEnvironment().withProperty("SAMPLE_PURGE_RUN_ID", RUN));
    }

    @Test void wrongOptInStopsBeforeDataSourceAndSqlInitialization() throws Exception {
        for (String confirmation : List.of("", "WRONG", "create_fresh_purge_demo", "CREATE_FRESH_PURGE_DEMO "))
            assertRejected(new MockEnvironment().withProperty("SAMPLE_PURGE_RUN_ID", RUN)
                    .withProperty("SAMPLE_PURGE_CONFIRM", confirmation));
    }

    @Test void missingRunStopsBeforeDataSourceAndSqlInitialization() throws Exception {
        assertRejected(new MockEnvironment().withProperty("SAMPLE_PURGE_CONFIRM", "CREATE_FRESH_PURGE_DEMO"));
    }

    @Test void malformedRunStopsBeforeDataSourceAndSqlInitialization() throws Exception {
        for (String run : List.of("", "arbitrary", RUN.substring(1), RUN.toUpperCase(), RUN + " ", "../" + RUN))
            assertRejected(new MockEnvironment().withProperty("SAMPLE_PURGE_RUN_ID", run)
                    .withProperty("SAMPLE_PURGE_CONFIRM", "CREATE_FRESH_PURGE_DEMO"));
    }

    @Test void validOptInAllowsSqlInitializationOnce() throws Exception {
        try (var fixture = new SqlInitializationFixture(validEnvironment())) {
            fixture.context.refresh();
            assertEquals(RUN, TenantPurgeOptIn.require(fixture.context.getEnvironment()));
            assertEquals(1, fixture.dataSources.get());
            assertEquals(1, fixture.initializers.get());
            verify(fixture.source, times(1)).getConnection();
            verify(fixture.statement, atLeastOnce()).execute(anyString());
        }
    }

    @Test void applicationExplicitlyImportsEarlyGuard() throws Exception {
        Import registration = TenantPurgeApplication.class.getDeclaredAnnotation(Import.class);
        assertNotNull(registration);
        assertTrue(Arrays.asList(registration.value()).contains(TenantPurgeOptIn.class));
        var factory = TenantPurgeOptIn.class.getDeclaredMethod("purgeOptIn", Environment.class);
        assertNotNull(factory.getDeclaredAnnotation(Bean.class));
        assertTrue(Modifier.isStatic(factory.getModifiers()));
        assertEquals(BeanFactoryPostProcessor.class, factory.getReturnType());
    }

    @Test void directRuntimeConstructionStillRejectsInvalidOptInWithoutDataSourceAccess() {
        var source = mock(DataSource.class);
        for (MockEnvironment environment : List.of(new MockEnvironment(),
                validEnvironment().withProperty("SAMPLE_PURGE_CONFIRM", "WRONG"),
                validEnvironment().withProperty("SAMPLE_PURGE_RUN_ID", "arbitrary"))) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> new TenantPurgeRuntime(source, environment));
            assertEquals("Fresh disposable purge opt-in required", failure.getMessage());
        }
        verifyNoInteractions(source);
    }

    private static MockEnvironment validEnvironment() {
        return new MockEnvironment().withProperty("SAMPLE_PURGE_RUN_ID", RUN)
                .withProperty("SAMPLE_PURGE_CONFIRM", "CREATE_FRESH_PURGE_DEMO");
    }

    private static void assertRejected(MockEnvironment environment) throws Exception {
        try (var fixture = new SqlInitializationFixture(environment)) {
            var failure = assertThrows(IllegalArgumentException.class, fixture.context::refresh);
            assertEquals("Fresh disposable purge opt-in required", failure.getMessage());
            assertEquals(0, fixture.dataSources.get());
            assertEquals(0, fixture.initializers.get());
            verifyNoInteractions(fixture.source, fixture.connection, fixture.statement);
        }
    }

    private static final class SqlInitializationFixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final AtomicInteger dataSources = new AtomicInteger();
        final AtomicInteger initializers = new AtomicInteger();
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final Statement statement = mock(Statement.class);

        SqlInitializationFixture(MockEnvironment environment) throws Exception {
            when(source.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(connection.getAutoCommit()).thenReturn(true);
            when(statement.getUpdateCount()).thenReturn(-1);
            context.setEnvironment(environment);
            context.register(TenantPurgeOptIn.class);
            context.registerBean("sentinelDataSource", DataSource.class, () -> {
                dataSources.incrementAndGet();
                return source;
            });
            context.registerBean("sentinelSqlInitializer", DataSourceScriptDatabaseInitializer.class, () -> {
                initializers.incrementAndGet();
                var settings = new DatabaseInitializationSettings();
                settings.setMode(DatabaseInitializationMode.ALWAYS);
                settings.setContinueOnError(false);
                settings.setSchemaLocations(List.of("classpath:schema-purge.sql"));
                return new DataSourceScriptDatabaseInitializer(context.getBean(DataSource.class), settings);
            });
        }

        @Override public void close() {
            context.close();
        }
    }
}

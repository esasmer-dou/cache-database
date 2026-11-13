package com.reactor.cachedb.spring.boot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BoundedMssqlTestDatabaseTest {
    private static final String NAME = "cachedb_fence_sarg_0123456789abcdef0123456789abcdef";
    private static final String DIRECTORY = "/var/opt/mssql/data/";
    private static final BoundedMssqlTestDatabase.ModelFiles MODEL =
            new BoundedMssqlTestDatabase.ModelFiles(2, 1, 1, 1024, 1024);

    @Test
    void initialCreateBoundsBothFilesAndPreservesAllowedCollations() {
        for (String collation : new String[] {
                null, "SQL_Latin1_General_CP1_CI_AS", "Latin1_General_100_CI_AS_SC"}) {
            String sql = BoundedMssqlTestDatabase.createSql(NAME, DIRECTORY, DIRECTORY, MODEL, collation);
            assertTrue(sql.startsWith("CREATE DATABASE [" + NAME + "]"));
            assertTrue(sql.contains("FILENAME=N'" + DIRECTORY + NAME + ".mdf'"));
            assertTrue(sql.contains("FILENAME=N'" + DIRECTORY + NAME + "_log.ldf'"));
            assertTrue(sql.contains("SIZE=8MB, MAXSIZE=64MB, FILEGROWTH=8MB"));
            assertTrue(sql.contains("SIZE=8MB, MAXSIZE=32MB, FILEGROWTH=8MB"));
            assertEquals(collation != null, sql.contains(" COLLATE "));
            if (collation != null) assertTrue(sql.endsWith(" COLLATE " + collation));
            assertFalse(sql.contains("ALTER DATABASE"));
            assertFalse(sql.contains("UNLIMITED"));
        }
    }

    @Test
    void bothOwnedPrefixesAndCanonicalDefaultDirectoryAreAccepted() {
        assertDoesNotThrow(() -> BoundedMssqlTestDatabase.createSql(
                "snapshot_off_0123456789abcdef0123456789abcdef",
                "/var/opt/mssql/data", DIRECTORY, MODEL, null));
    }

    @Test
    void arbitrarySharedOrQuotedPathsAreRejected() {
        for (String path : new String[] {
                null, "", "/tmp/", "/var/opt/mssql/data/../", "C:\\data\\", DIRECTORY + "'"}) {
            assertThrows(RuntimeException.class,
                    () -> BoundedMssqlTestDatabase.createSql(NAME, path, DIRECTORY, MODEL, null));
            assertThrows(RuntimeException.class,
                    () -> BoundedMssqlTestDatabase.createSql(NAME, DIRECTORY, path, MODEL, null));
        }
    }

    @Test
    void oversizedOrUnexpectedModelFilesAreRejectedBeforeDdl() {
        for (var model : new BoundedMssqlTestDatabase.ModelFiles[] {
                null,
                new BoundedMssqlTestDatabase.ModelFiles(3, 2, 1, 1024, 1024),
                new BoundedMssqlTestDatabase.ModelFiles(2, 0, 2, 1024, 1024),
                new BoundedMssqlTestDatabase.ModelFiles(2, 1, 1, 1025, 1024),
                new BoundedMssqlTestDatabase.ModelFiles(2, 1, 1, 1024, 1025),
                new BoundedMssqlTestDatabase.ModelFiles(2, 1, 1, 0, 1024),
                new BoundedMssqlTestDatabase.ModelFiles(2, 1, 1, 1024, 0)}) {
            assertThrows(IllegalStateException.class,
                    () -> BoundedMssqlTestDatabase.createSql(NAME, DIRECTORY, DIRECTORY, model, null));
        }
    }

    @Test
    void sharedNamesAndUnreviewedCollationAreRejected() {
        for (String name : new String[] {"master", "snapshot_off_short", NAME + "'; DROP DATABASE master;--"}) {
            assertThrows(IllegalStateException.class,
                    () -> BoundedMssqlTestDatabase.createSql(name, DIRECTORY, DIRECTORY, MODEL, null));
        }
        assertThrows(IllegalArgumentException.class, () -> BoundedMssqlTestDatabase.createSql(
                NAME, DIRECTORY, DIRECTORY, MODEL, "Latin1_General_100_CI_AS_SC; SELECT 1"));
    }

    @Test
    void realCreatePreflightsMetadataAndAppliesDdlTimeout() throws Exception {
        var jdbc = new Jdbc();
        try (var owned = jdbc.create()) {
            assertTrue(owned.name().matches("cachedb_fence_sarg_[0-9a-f]{32}"));
            verify(jdbc.metadata).setQueryTimeout(10);
            verify(jdbc.metadata).setString(1, owned.name());
            verify(jdbc.create).setQueryTimeout(15);
            var sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc.create).execute(sql.capture());
            assertEquals(BoundedMssqlTestDatabase.createSql(
                    owned.name(), DIRECTORY, DIRECTORY, MODEL, null), sql.getValue());
            var order = inOrder(jdbc.metadata, jdbc.rows, jdbc.create);
            order.verify(jdbc.metadata).executeQuery();
            order.verify(jdbc.rows).close();
            order.verify(jdbc.metadata).close();
            order.verify(jdbc.create).execute(anyString());
        }
        verify(jdbc.drop).setQueryTimeout(15);
        var dropSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc.drop).execute(dropSql.capture());
        assertTrue(dropSql.getValue().contains("DROP DATABASE ["));
        assertFalse(dropSql.getValue().contains("DROP DATABASE [master]"));
    }

    @Test
    void existingDatabaseNeverGrantsCleanupOwnership() throws Exception {
        var jdbc = new Jdbc();
        when(jdbc.rows.getObject(8)).thenReturn(23);
        assertThrows(IllegalStateException.class, jdbc::create);
        jdbc.verifyNoDdl();
    }

    @Test
    void missingOrAmbiguousMetadataNeverRunsDdl() throws Exception {
        var missing = new Jdbc();
        when(missing.rows.next()).thenReturn(false);
        assertThrows(IllegalStateException.class, missing::create);
        missing.verifyNoDdl();
        var ambiguous = new Jdbc();
        when(ambiguous.rows.next()).thenReturn(true, true);
        assertThrows(IllegalStateException.class, ambiguous::create);
        ambiguous.verifyNoDdl();
    }

    @Test
    void wrongCatalogOrOversizedModelNeverRunsDdl() throws Exception {
        var wrongCatalog = new Jdbc();
        when(wrongCatalog.connection.getCatalog()).thenReturn("shared_application");
        assertThrows(IllegalStateException.class, wrongCatalog::create);
        wrongCatalog.verifyNoDdl();
        var oversized = new Jdbc();
        when(oversized.rows.getLong(6)).thenReturn(1025L);
        assertThrows(IllegalStateException.class, oversized::create);
        oversized.verifyNoDdl();
    }

    @Test
    void uncertainCreateFailureClosesFirstConnectionThenCleansOnlyOwnedName() throws Exception {
        var jdbc = new Jdbc();
        var failure = new SQLException("CREATE timed out");
        when(jdbc.create.execute(anyString())).thenThrow(failure);
        assertSame(failure, assertThrows(SQLException.class, jdbc::create));
        var order = inOrder(jdbc.admin, jdbc.connection, jdbc.drop);
        order.verify(jdbc.admin).getConnection();
        order.verify(jdbc.connection).close();
        order.verify(jdbc.admin).getConnection();
        order.verify(jdbc.drop).execute(anyString());
        var createSql = ArgumentCaptor.forClass(String.class);
        var dropSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc.create).execute(createSql.capture());
        verify(jdbc.drop).execute(dropSql.capture());
        String name = createSql.getValue().split("\\[", 2)[1].split("]", 2)[0];
        assertTrue(dropSql.getValue().contains("DROP DATABASE [" + name + "]"));
    }

    @Test
    void explicitCreateNameCollisionNeverDropsExistingDatabase() throws Exception {
        var jdbc = new Jdbc();
        var failure = new SQLException("Already exists", "S0001", 1801);
        when(jdbc.create.execute(anyString())).thenThrow(failure);
        assertSame(failure, assertThrows(SQLException.class, jdbc::create));
        verify(jdbc.admin, times(1)).getConnection();
        verifyNoInteractions(jdbc.drop);
    }

    @Test
    void cleanupFailureIsSuppressedOnOriginalCreateFailure() throws Exception {
        var jdbc = new Jdbc();
        var primary = new SQLException("CREATE failed");
        var cleanup = new SQLException("DROP failed");
        when(jdbc.create.execute(anyString())).thenThrow(primary);
        when(jdbc.drop.execute(anyString())).thenThrow(cleanup);
        assertSame(primary, assertThrows(SQLException.class, jdbc::create));
        assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed());
        verify(jdbc.cleanupConnection).close();
    }

    @Test
    void poolCloseFailureDoesNotPreventOwnedDropAndRetainsFirstFailure() throws Exception {
        var jdbc = new Jdbc();
        var owned = jdbc.create();
        var poolFailure = new IllegalStateException("pool close");
        var dropFailure = new SQLException("DROP failed");
        when(jdbc.drop.execute(anyString())).thenThrow(dropFailure);
        AutoCloseable pool = () -> { throw poolFailure; };
        assertSame(poolFailure, assertThrows(IllegalStateException.class,
                () -> BoundedMssqlTestDatabase.closeResources(pool, owned)));
        assertArrayEquals(new Throwable[] {dropFailure}, poolFailure.getSuppressed());
        verify(jdbc.drop).execute(anyString());
    }

    @Test
    void successfulCloseIsIdempotent() throws Exception {
        var jdbc = new Jdbc();
        var owned = jdbc.create();
        owned.close();
        owned.close();
        verify(jdbc.drop, times(1)).execute(anyString());
        verify(jdbc.admin, times(2)).getConnection();
    }

    @Test
    void constructorFailureCleanupRetainsPrimaryAndClosesAllResources() {
        var primary = new IllegalStateException("setup");
        var poolFailure = new IllegalStateException("pool");
        var dropFailure = new AssertionError("drop");
        AutoCloseable pool = () -> { throw poolFailure; };
        AutoCloseable database = () -> { throw dropFailure; };
        BoundedMssqlTestDatabase.closeAfterFailure(primary, null, pool, database);
        assertArrayEquals(new Throwable[] {poolFailure, dropFailure}, primary.getSuppressed());
    }

    private static final class Jdbc {
        final DataSource admin = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final Connection cleanupConnection = mock(Connection.class);
        final PreparedStatement metadata = mock(PreparedStatement.class);
        final ResultSet rows = mock(ResultSet.class);
        final Statement create = mock(Statement.class);
        final Statement drop = mock(Statement.class);

        Jdbc() throws Exception {
            when(admin.getConnection()).thenReturn(connection, cleanupConnection);
            when(connection.getCatalog()).thenReturn("master");
            when(cleanupConnection.getCatalog()).thenReturn("master");
            when(connection.prepareStatement(anyString())).thenReturn(metadata);
            when(connection.createStatement()).thenReturn(create);
            when(cleanupConnection.createStatement()).thenReturn(drop);
            when(metadata.executeQuery()).thenReturn(rows);
            when(rows.next()).thenReturn(true, false);
            when(rows.getString(1)).thenReturn(DIRECTORY);
            when(rows.getString(2)).thenReturn(DIRECTORY);
            when(rows.getLong(3)).thenReturn(2L);
            when(rows.getLong(4)).thenReturn(1L);
            when(rows.getLong(5)).thenReturn(1L);
            when(rows.getLong(6)).thenReturn(1024L);
            when(rows.getLong(7)).thenReturn(1024L);
        }

        BoundedMssqlTestDatabase create() throws Exception {
            return BoundedMssqlTestDatabase.create(admin, BoundedMssqlTestDatabase.Scope.WRITE_FENCE, null);
        }

        void verifyNoDdl() throws Exception {
            verify(connection, never()).createStatement();
            verify(admin, times(1)).getConnection();
            verifyNoInteractions(create, drop);
        }
    }
}

package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import static org.mockito.Mockito.*;

class InitialSqlSandboxCleanupTest {
    @Test
    void emptyScopeValidationIsBoundedAndRejectsAnyObject()throws Exception{
        for(String provider:List.of("postgres","mssql","oracle")){
            var connection=mock(Connection.class);var query=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
            when(connection.prepareStatement(anyString())).thenReturn(query);
            when(query.executeQuery()).thenReturn(rows);
            when(rows.next()).thenReturn(false,true);
            InitialSqlSandbox.assertEmpty(connection,provider,"cachedb_v019_initial_0123abcd");
            assertThrows(IllegalStateException.class,()->InitialSqlSandbox.assertEmpty(connection,provider,"cachedb_v019_initial_0123abcd"));
            verify(query,times(2)).setQueryTimeout(10);verify(query,times(2)).setMaxRows(1);
            verify(query,times(2)).close();verify(rows,times(2)).close();
            if(provider.equals("postgres"))verify(query,times(2)).setString(1,"cachedb_v019_initial_0123abcd");
        }
    }

    @Test
    void emptyScopeRejectsUnownedNamesAndUnknownProvidersBeforeSql(){
        var connection=mock(Connection.class);
        assertThrows(IllegalArgumentException.class,()->InitialSqlSandbox.assertEmpty(connection,"postgres","public"));
        assertThrows(IllegalArgumentException.class,()->InitialSqlSandbox.assertEmpty(connection,"other","cachedb_v019_initial_0123abcd"));
        verifyNoInteractions(connection);
    }

    @Test
    void closesSourceThenOwnedScopeThenAdmin()throws Exception{
        var closed=new ArrayList<String>();
        InitialSqlSandbox.closeSandbox(resource(closed,"source",null),
                resource(closed,"drop-scope",null),resource(closed,"admin",null));
        assertEquals(List.of("source","drop-scope","admin"),closed);
    }

    @Test
    void sourceFailureStillDropsScopeAndClosesAdmin(){
        var closed=new ArrayList<String>();
        var primary=new IOException("source close failed");
        var dropFailure=new SQLException("drop failed");
        var adminFailure=new IllegalStateException("admin close failed");
        var actual=assertThrows(IOException.class,()->InitialSqlSandbox.closeSandbox(
                resource(closed,"source",primary),resource(closed,"drop-scope",dropFailure),
                resource(closed,"admin",adminFailure)));
        assertSame(primary,actual);
        assertArrayEquals(new Throwable[]{dropFailure,adminFailure},actual.getSuppressed());
        assertEquals(List.of("source","drop-scope","admin"),closed);
    }

    @Test
    void dropFailureRemainsPrimaryWhenAdminAlsoFails(){
        var closed=new ArrayList<String>();
        var primary=new SQLException("drop failed");
        var secondary=new IllegalStateException("admin close failed");
        assertSame(primary,assertThrows(SQLException.class,()->InitialSqlSandbox.closeSandbox(
                resource(closed,"source",null),resource(closed,"drop-scope",primary),resource(closed,"admin",secondary))));
        assertArrayEquals(new Throwable[]{secondary},primary.getSuppressed());
        assertEquals(List.of("source","drop-scope","admin"),closed);
    }

    @Test
    void fatalSourceFailureStillAttemptsBothRemainingResources(){
        var closed=new ArrayList<String>();
        var primary=new AssertionError("source close failed");
        assertSame(primary,assertThrows(AssertionError.class,()->InitialSqlSandbox.closeSandbox(
                resource(closed,"source",primary),resource(closed,"drop-scope",null),resource(closed,"admin",null))));
        assertEquals(List.of("source","drop-scope","admin"),closed);
    }

    @Test
    void absentSourceDuringFailedConstructionStillCleansScopeAndAdmin()throws Exception{
        var closed=new ArrayList<String>();
        InitialSqlSandbox.closeSandbox(null,resource(closed,"drop-scope",null),resource(closed,"admin",null));
        assertEquals(List.of("drop-scope","admin"),closed);
    }

    @Test
    void adminOnlyFailureIsNotSwallowed(){
        var closed=new ArrayList<String>();
        var primary=new IOException("admin close failed");
        assertSame(primary,assertThrows(IOException.class,()->InitialSqlSandbox.closeSandbox(
                resource(closed,"source",null),resource(closed,"drop-scope",null),resource(closed,"admin",primary))));
        assertEquals(List.of("source","drop-scope","admin"),closed);
    }

    private static AutoCloseable resource(List<String> closed,String name,Throwable failure){
        return ()->{
            closed.add(name);
            if(failure instanceof Error fatal)throw fatal;
            if(failure!=null)throw (Exception)failure;
        };
    }
}

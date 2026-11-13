package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratedInitialFixtureCleanupTest {
    @Test
    void ownedFixtureDropsItsScopeAfterEveryRuntimeResourceCloses()throws Exception{
        var closed=new ArrayList<String>();
        GeneratedInitialSqlSandbox.closeFixture(false,sandbox(closed,null,null,null),
                resource(closed,"peer",null),resource(closed,"database",null),
                resource(closed,"wire",null),null,resource(closed,"redis",null),
                resource(closed,"compiled",null));
        assertEquals(List.of("peer","database","wire","redis","compiled","source","drop-scope","admin"),closed);
    }

    @Test
    void borrowedSandboxRemainsOpenUntilItsActualOwnerClosesIt()throws Exception{
        var closed=new ArrayList<String>();
        var sandbox=sandbox(closed,null,null,null);
        GeneratedInitialSqlSandbox.closeFixture(true,sandbox,resource(closed,"runtime",null));
        assertEquals(List.of("runtime"),closed);
        sandbox.close();
        assertEquals(List.of("runtime","source","drop-scope","admin"),closed);
    }

    @Test
    void runtimeFailuresDoNotSkipOtherResourcesOrOwnedSandbox(){
        var closed=new ArrayList<String>();
        var primary=new IOException("runtime close failed");
        var secondary=new IllegalStateException("compiled close failed");
        var dropFailure=new SQLException("scope cleanup failed");
        var actual=assertThrows(IOException.class,()->GeneratedInitialSqlSandbox.closeFixture(false,
                sandbox(closed,null,dropFailure,null),resource(closed,"runtime",primary),
                resource(closed,"compiled",secondary)));
        assertSame(primary,actual);
        assertArrayEquals(new Throwable[]{secondary,dropFailure},actual.getSuppressed());
        assertEquals(List.of("runtime","compiled","source","drop-scope","admin"),closed);
    }

    @Test
    void fatalRuntimeFailureStillClosesOwnedSandbox(){
        var closed=new ArrayList<String>();
        var primary=new AssertionError("runtime close failed");
        var actual=assertThrows(AssertionError.class,()->GeneratedInitialSqlSandbox.closeFixture(false,
                sandbox(closed,null,null,null),resource(closed,"runtime",primary),
                resource(closed,"compiled",null)));
        assertSame(primary,actual);
        assertEquals(List.of("runtime","compiled","source","drop-scope","admin"),closed);
    }

    @Test
    void borrowedSandboxIsNotClosedWhenItsRuntimeFails(){
        var closed=new ArrayList<String>();
        var primary=new IOException("runtime close failed");
        assertSame(primary,assertThrows(IOException.class,()->GeneratedInitialSqlSandbox.closeFixture(true,
                sandbox(closed,null,null,null),resource(closed,"runtime",primary),
                resource(closed,"compiled",null))));
        assertEquals(List.of("runtime","compiled"),closed);
    }

    @Test
    void sourceClosureFailureStillAttemptsScopeRemovalAndAdminClosure(){
        var closed=new ArrayList<String>();
        var primary=new IllegalStateException("source close failed");
        var dropFailure=new SQLException("scope cleanup failed");
        var adminFailure=new IllegalArgumentException("admin close failed");
        var actual=assertThrows(IllegalStateException.class,
                ()->sandbox(closed,primary,dropFailure,adminFailure).close());
        assertSame(primary,actual);
        assertArrayEquals(new Throwable[]{dropFailure,adminFailure},actual.getSuppressed());
        assertEquals(List.of("source","drop-scope","admin"),closed);
    }

    @Test
    void scopeCleanupFailureIsNotSwallowedAndStillClosesAdmin(){
        var closed=new ArrayList<String>();
        var primary=new SQLException("scope cleanup failed");
        assertSame(primary,assertThrows(SQLException.class,
                ()->GeneratedInitialSqlSandbox.closeFixture(false,sandbox(closed,null,primary,null))));
        assertEquals(List.of("source","drop-scope","admin"),closed);
    }

    @Test
    void constructionFailureClosesEverySuccessfullyAcquiredPrefix(){
        for(int acquired=0;acquired<=7;acquired++){
            var closed=new ArrayList<String>();
            var primary=new AssertionError("construction failed after "+acquired);
            String[] names={"compiled","sandbox","redis","peer-redis","wire","database","peer"};
            var resources=new AutoCloseable[7];
            for(int i=0;i<acquired;i++)resources[i]=i==1?sandbox(closed,null,null,null):resource(closed,names[i],null);
            GeneratedInitialSqlSandbox.closeAfterFailedConstruction(primary,false,resources[1],
                    resources[6],resources[5],resources[4],resources[3],resources[2],resources[0]);
            var expected=new ArrayList<String>();
            for(int i=acquired-1;i>=2;i--)expected.add(names[i]);
            if(acquired>0)expected.add("compiled");
            if(acquired>1)expected.addAll(List.of("source","drop-scope","admin"));
            assertEquals(expected,closed,"Acquired prefix "+acquired);
            assertEquals(0,primary.getSuppressed().length);
        }
    }

    @Test
    void failedConstructionNeverClosesBorrowedSql(){
        var closed=new ArrayList<String>();
        var primary=new IOException("construction failed");
        var borrowed=sandbox(closed,null,null,null);
        GeneratedInitialSqlSandbox.closeAfterFailedConstruction(primary,true,borrowed,
                resource(closed,"runtime",null),resource(closed,"compiled",null));
        assertEquals(List.of("runtime","compiled"),closed);
        assertEquals(0,primary.getSuppressed().length);
    }

    @Test
    void failedConstructionRetainsPrimaryAndAllCleanupFailures(){
        var closed=new ArrayList<String>();
        var primary=new AssertionError("construction failed");
        var runtimeFailure=new IllegalStateException("runtime close failed");
        var compiledFailure=new IOException("compiled close failed");
        var dropFailure=new SQLException("scope cleanup failed");
        GeneratedInitialSqlSandbox.closeAfterFailedConstruction(primary,false,sandbox(closed,null,dropFailure,null),
                resource(closed,"runtime",runtimeFailure),resource(closed,"compiled",compiledFailure));
        assertArrayEquals(new Throwable[]{runtimeFailure},primary.getSuppressed());
        assertArrayEquals(new Throwable[]{compiledFailure,dropFailure},runtimeFailure.getSuppressed());
        assertEquals(List.of("runtime","compiled","source","drop-scope","admin"),closed);
    }

    @Test
    void reusedPrimaryCleanupFailureDoesNotCauseSelfSuppression(){
        var primary=new AssertionError("same failure");
        var closed=new ArrayList<String>();
        GeneratedInitialSqlSandbox.closeAfterFailedConstruction(primary,true,null,
                resource(closed,"runtime",primary),resource(closed,"compiled",null));
        assertEquals(List.of("runtime","compiled"),closed);
        assertEquals(0,primary.getSuppressed().length);
    }

    private static AutoCloseable sandbox(List<String> closed,Throwable sourceFailure,
            Throwable dropFailure,Throwable adminFailure){
        return ()->GeneratedInitialSqlSandbox.closeSandbox(resource(closed,"source",sourceFailure),
                resource(closed,"drop-scope",dropFailure),resource(closed,"admin",adminFailure));
    }

    private static AutoCloseable resource(List<String> closed,String name,Throwable failure){
        return ()->{
            closed.add(name);
            if(failure instanceof Error fatal)throw fatal;
            if(failure!=null)throw (Exception)failure;
        };
    }
}

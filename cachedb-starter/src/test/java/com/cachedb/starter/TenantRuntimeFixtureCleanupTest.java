package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenantRuntimeFixtureCleanupTest {
    @Test
    void failedPairAcquisitionClosesEverySuccessfullyAcquiredPrefix(){
        String[] names={"redis","owned-sql","wire"};
        for(int acquired=0;acquired<=names.length;acquired++){
            var closed=new ArrayList<String>();
            var primary=new AssertionError("construction failed after "+acquired);
            var resources=new AutoCloseable[names.length];
            for(int i=0;i<acquired;i++)resources[i]=resource(closed,names[i],null);
            TenantRuntimeInitialFixture.closeAfterFailedConstruction(primary,resources[2],resources[0],resources[1]);
            var expected=new ArrayList<String>();
            if(acquired>2)expected.add("wire");
            if(acquired>0)expected.add("redis");
            if(acquired>1)expected.add("owned-sql");
            assertEquals(expected,closed,"Acquired prefix "+acquired);
            assertEquals(0,primary.getSuppressed().length);
        }
    }

    @Test
    void failedProcessRootClosesEveryOwnedPrefixWithoutClosingBorrowedSql(){
        String[] names={"redis","wire","database"};
        for(int acquired=0;acquired<=names.length;acquired++){
            var closed=new ArrayList<String>();
            var primary=new AssertionError("process construction failed after "+acquired);
            var borrowed=resource(closed,"borrowed-sql",null);
            var resources=new AutoCloseable[names.length];
            for(int i=0;i<acquired;i++)resources[i]=resource(closed,names[i],null);
            TenantRuntimeInitialFixture.closeAfterFailedConstruction(primary,resources[2],resources[1],resources[0]);
            var expected=new ArrayList<String>();
            for(int i=acquired-1;i>=0;i--)expected.add(names[i]);
            assertEquals(expected,closed,"Acquired process prefix "+acquired);
            assertEquals(0,primary.getSuppressed().length);
            assertDoesNotThrow(borrowed::close);
            expected.add("borrowed-sql");
            assertEquals(expected,closed);
        }
    }

    @Test
    void failedOwningRootClosesRuntimeAndRedisBeforeItsSqlOwner(){
        var closed=new ArrayList<String>();
        var primary=new IllegalStateException("root construction failed");
        TenantRuntimeInitialFixture.closeAfterFailedConstruction(primary,resource(closed,"database",null),
                resource(closed,"redis",null),resource(closed,"owned-sql",null));
        assertEquals(List.of("database","redis","owned-sql"),closed);
        assertEquals(0,primary.getSuppressed().length);
    }

    @Test
    void failedBorrowingRootLeavesItsSqlOwnerOpen(){
        var closed=new ArrayList<String>();
        var primary=new AssertionError("borrower construction failed");
        var borrowed=resource(closed,"borrowed-sql",null);
        TenantRuntimeInitialFixture.closeAfterFailedConstruction(primary,resource(closed,"database",null),
                resource(closed,"redis",null),null);
        assertEquals(List.of("database","redis"),closed);
        assertDoesNotThrow(borrowed::close);
        assertEquals(List.of("database","redis","borrowed-sql"),closed);
    }

    @Test
    void checkedAndFatalCleanupFailuresAreSuppressedWithoutSkippingTheOwner(){
        var closed=new ArrayList<String>();
        var primary=new IllegalArgumentException("construction failed");
        var checked=new IOException("runtime close failed");
        var fatal=new AssertionError("redis close failed");
        var sqlFailure=new IllegalStateException("SQL close failed");
        TenantRuntimeInitialFixture.closeAfterFailedConstruction(primary,resource(closed,"database",checked),
                resource(closed,"redis",fatal),resource(closed,"owned-sql",sqlFailure));
        assertArrayEquals(new Throwable[]{checked,fatal,sqlFailure},primary.getSuppressed());
        assertEquals(List.of("database","redis","owned-sql"),closed);
    }

    @Test
    void reusedPrimaryCleanupFailureDoesNotPreventRemainingClosure(){
        var closed=new ArrayList<String>();
        var primary=new AssertionError("same failure");
        TenantRuntimeInitialFixture.closeAfterFailedConstruction(primary,resource(closed,"database",primary),
                null,resource(closed,"owned-sql",null));
        assertEquals(List.of("database","owned-sql"),closed);
        assertEquals(0,primary.getSuppressed().length);
    }

    private static AutoCloseable resource(List<String> closed,String name,Throwable failure){
        return ()->{
            closed.add(name);
            if(failure instanceof Error fatal)throw fatal;
            if(failure!=null)throw (Exception)failure;
        };
    }
}

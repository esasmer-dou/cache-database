package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratedInitialSqlSandboxOracleBoundsTest {
    private static final String NAME="cachedb_v019_initial_0123abcd";
    private static final String FILE="/opt/oracle/oradata/FREE/FREEPDB1/users01.dbf";
    private static final String PASSWORD="OwnedFixture_123";
    private static final GeneratedInitialSqlSandbox.OracleFreshness FRESH=(owner,file)->{
        assertEquals("CACHEDB_V019_INITIAL_0123ABCD",owner);
        assertEquals("/opt/oracle/oradata/FREE/FREEPDB1/"+NAME+".dbf",file);
        GeneratedInitialSqlSandbox.OracleStorage.requireFresh(0,0,0);
    };

    @Test
    void allocatesOnlyAnOwnedBoundedDatafileAndExplicitUserQuota()throws Exception{
        var storage=new GeneratedInitialSqlSandbox.OracleStorage(NAME);
        var sql=new ArrayList<String>();
        storage.create((owner,file)->{
            assertFalse(storage.pending(),"Freshness must precede cleanup ownership");FRESH.verify(owner,file);
        },command->{assertTrue(storage.pending(),"Attempt must precede DDL");sql.add(command);},"FREEPDB1",FILE,PASSWORD);
        assertEquals(List.of(
                "CREATE SMALLFILE TABLESPACE "+NAME+" DATAFILE '/opt/oracle/oradata/FREE/FREEPDB1/"+NAME
                        +".dbf' SIZE 8M AUTOEXTEND ON NEXT 8M MAXSIZE 64M EXTENT MANAGEMENT LOCAL SEGMENT SPACE MANAGEMENT AUTO",
                "CREATE USER "+NAME+" IDENTIFIED BY \""+PASSWORD+"\" DEFAULT TABLESPACE "+NAME+" QUOTA 64M ON "+NAME,
                "GRANT CREATE SESSION,CREATE TABLE TO "+NAME),sql);
        assertTrue(storage.pending());
        assertThrows(IllegalStateException.class,()->storage.create(FRESH,sql::add,"FREEPDB1",FILE,PASSWORD));
        assertEquals(3,sql.size(),"Repeated allocation must not issue DDL");
    }

    @Test
    void rejectsSharedOrMalformedNamesBeforeAnyDdl(){
        for(String name:new String[]{null,"","USERS","SYSTEM","PDB$SEED","cachedb_v019_initial_0123abc",
                "cachedb_v019_initial_0123abcde","cachedb_v019_initial_0123ABCD","cachedb_v019_initial_0123abcd'"}){
            assertThrows(IllegalStateException.class,()->new GeneratedInitialSqlSandbox.OracleStorage(name));
        }
    }

    @Test
    void rejectsRootSeedAndUnsafeFileMetadataBeforeAnyDdl(){
        for(String container:new String[]{null,""," ","CDB$ROOT","cdb$root","PDB$SEED","pdb$seed"}){
            var storage=new GeneratedInitialSqlSandbox.OracleStorage(NAME);
            var sql=new ArrayList<String>();
            assertThrows(IllegalStateException.class,()->storage.create(FRESH,sql::add,container,FILE,PASSWORD));
            assertFalse(storage.pending());assertTrue(sql.isEmpty());
        }
        for(String file:new String[]{null,"","users01.dbf","/users01.dbf","/data/../users01.dbf",
                "/data/./users01.dbf","/data//users01.dbf","/data/users01.dbf'","/data/users01.dbf\n",
                "/data/users01.dbf\r","/data/users01.dbf\0","+DATA/FREE/users01.dbf","C:\\data\\users01.dbf"}){
            var storage=new GeneratedInitialSqlSandbox.OracleStorage(NAME);
            var sql=new ArrayList<String>();
            assertThrows(IllegalStateException.class,()->storage.create(FRESH,sql::add,"FREEPDB1",file,PASSWORD));
            assertFalse(storage.pending());assertTrue(sql.isEmpty());
        }
    }

    @Test
    void rejectsInvalidCredentialsBeforeAllocatingADatafile(){
        for(String password:new String[]{null,"","x".repeat(129),"private\nvalue","private\rvalue","private\0value"}){
            var storage=new GeneratedInitialSqlSandbox.OracleStorage(NAME);
            var sql=new ArrayList<String>();
            assertThrows(IllegalArgumentException.class,()->storage.create(FRESH,sql::add,"FREEPDB1",FILE,password));
            assertFalse(storage.pending());assertTrue(sql.isEmpty());
        }
    }

    @Test
    void explicitTablespaceCollisionNeverClaimsOrDropsAnExistingObject()throws Exception{
        for(int code:new int[]{955,1543}){
            var storage=new GeneratedInitialSqlSandbox.OracleStorage(NAME);
            var failure=new SQLException("fixture collision","42000",code);
            assertSame(failure,assertThrows(SQLException.class,
                    ()->storage.create(FRESH,sql->{throw failure;},"FREEPDB1",FILE,PASSWORD)));
            var cleanup=new ArrayList<String>();storage.drop(cleanup::add);
            assertFalse(storage.pending());assertTrue(cleanup.isEmpty());
        }
    }

    @Test
    void explicitUserCollisionRemovesOnlyTheOwnedTablespaceNeverTheCollidingUser()throws Exception{
        for(int code:new int[]{955,1920}){
            var storage=new GeneratedInitialSqlSandbox.OracleStorage(NAME);
            var sql=new ArrayList<String>();
            var failure=new SQLException("fixture user collision","42000",code);
            assertSame(failure,assertThrows(SQLException.class,()->storage.create(FRESH,command->{
                sql.add(command);if(sql.size()==2)throw failure;
            },"FREEPDB1",FILE,PASSWORD)));
            var cleanup=new ArrayList<String>();storage.drop(cleanup::add);
            assertEquals(List.of("DROP TABLESPACE "+NAME+" INCLUDING CONTENTS AND DATAFILES"),cleanup);
            assertFalse(storage.pending());
        }
    }

    @Test
    void failedGrantStillDropsUserThenOwnedDatafileAndRepeatedCleanupIsANoop()throws Exception{
        var storage=new GeneratedInitialSqlSandbox.OracleStorage(NAME);
        var sql=new ArrayList<String>();
        var failure=new SQLException("fixture grant failure","42000",1031);
        assertSame(failure,assertThrows(SQLException.class,()->storage.create(FRESH,command->{
            sql.add(command);if(sql.size()==3)throw failure;
        },"FREEPDB1",FILE,PASSWORD)));
        var cleanup=new ArrayList<String>();storage.drop(cleanup::add);storage.drop(cleanup::add);
        assertEquals(List.of("DROP USER "+NAME+" CASCADE",
                "DROP TABLESPACE "+NAME+" INCLUDING CONTENTS AND DATAFILES"),cleanup);
        assertFalse(storage.pending());
    }

    @Test
    void cleanupAttemptsBothObjectsAndRetainsBothFailuresThenCanRetry()throws Exception{
        var storage=allocated();
        var userFailure=new SQLException("fixture active session","42000",1940);
        var fileFailure=new SQLException("fixture file error","42000",1110);
        var cleanup=new ArrayList<String>();
        assertSame(userFailure,assertThrows(SQLException.class,()->storage.drop(command->{
            cleanup.add(command);throw command.startsWith("DROP USER")?userFailure:fileFailure;
        })));
        assertEquals(List.of("DROP USER "+NAME+" CASCADE",
                "DROP TABLESPACE "+NAME+" INCLUDING CONTENTS AND DATAFILES"),cleanup);
        assertArrayEquals(new Throwable[]{fileFailure},userFailure.getSuppressed());
        assertTrue(storage.pending());
        cleanup.clear();storage.drop(cleanup::add);assertEquals(2,cleanup.size());assertFalse(storage.pending());
    }

    @Test
    void missingObjectsAreIdempotentButUnexpectedErrorsAreNotSwallowed()throws Exception{
        var storage=allocated();
        var cleanup=new ArrayList<String>();
        storage.drop(command->{
            cleanup.add(command);throw new SQLException("missing","42000",command.startsWith("DROP USER")?1918:959);
        });
        storage.drop(cleanup::add);assertEquals(2,cleanup.size());assertFalse(storage.pending());
        var other=allocated();
        var unexpected=new SQLException("not a missing user","42000",959);
        assertSame(unexpected,assertThrows(SQLException.class,()->other.drop(command->{
            if(command.startsWith("DROP USER"))throw unexpected;
        })));
        assertTrue(other.pending());
        cleanup.clear();other.drop(cleanup::add);
        assertEquals(List.of("DROP USER "+NAME+" CASCADE"),cleanup);assertFalse(other.pending());
    }

    @Test
    void sanitizationRetainsCleanupCodesWithoutRawSqlOrCredentialMessages(){
        var failure=new SQLException("private primary SQL "+PASSWORD,"42000",1031);
        failure.addSuppressed(new SQLException("private cleanup SQL "+PASSWORD,"42000",1940));
        var sanitized=GeneratedInitialSqlSandbox.safe(failure);
        assertEquals(1031,sanitized.getErrorCode());assertEquals("42000",sanitized.getSQLState());
        assertEquals(1,sanitized.getSuppressed().length);
        assertEquals(1940,assertInstanceOf(SQLException.class,sanitized.getSuppressed()[0]).getErrorCode());
        assertFalse(sanitized.toString().contains(PASSWORD));
        assertFalse(sanitized.getSuppressed()[0].toString().contains(PASSWORD));assertNull(sanitized.getCause());
    }

    @Test
    void preflightCollisionsRejectEveryOwnedNameAndFileBeforeAnyDdl()throws Exception{
        for(long[] counts:new long[][]{{1,0,0},{0,1,0},{0,0,1},{2,0,0},{0,0,-1}}){
            var storage=new GeneratedInitialSqlSandbox.OracleStorage(NAME);
            var sql=new ArrayList<String>();
            assertThrows(IllegalStateException.class,()->storage.create((owner,file)->{
                FRESH.verify(owner,file);GeneratedInitialSqlSandbox.OracleStorage.requireFresh(counts[0],counts[1],counts[2]);
            },sql::add,"FREEPDB1",FILE,PASSWORD));
            storage.drop(sql::add);assertTrue(sql.isEmpty());assertFalse(storage.pending());
        }
    }

    @Test
    void failedFreshnessReadNeverAcquiresCleanupOwnership()throws Exception{
        var storage=new GeneratedInitialSqlSandbox.OracleStorage(NAME);
        var sql=new ArrayList<String>();var failure=new SQLTimeoutException("private preflight timeout","HYT00");
        assertSame(failure,assertThrows(SQLTimeoutException.class,()->storage.create((owner,file)->{
            throw failure;
        },sql::add,"FREEPDB1",FILE,PASSWORD)));
        storage.drop(sql::add);assertTrue(sql.isEmpty());assertFalse(storage.pending());
    }

    @Test
    void ambiguousTablespaceTimeoutStillAttemptsOwnedDatafileCleanup()throws Exception{
        var storage=new GeneratedInitialSqlSandbox.OracleStorage(NAME);
        var sql=new ArrayList<String>();var failure=new SQLTimeoutException("private create timeout","HYT00");
        assertSame(failure,assertThrows(SQLTimeoutException.class,()->storage.create(FRESH,command->{
            assertTrue(storage.tablespaceAttempted);assertFalse(storage.userAttempted);sql.add(command);throw failure;
        },"FREEPDB1",FILE,PASSWORD)));
        assertEquals(1,sql.size());assertTrue(storage.pending());sql.clear();
        storage.drop(sql::add);storage.drop(sql::add);
        assertEquals(List.of("DROP TABLESPACE "+NAME+" INCLUDING CONTENTS AND DATAFILES"),sql);
        assertFalse(storage.pending());
    }

    @Test
    void ambiguousUserTimeoutStillAttemptsBothOwnedObjects()throws Exception{
        var storage=new GeneratedInitialSqlSandbox.OracleStorage(NAME);
        var sql=new ArrayList<String>();var failure=new SQLTimeoutException("private user timeout","HYT00");
        assertSame(failure,assertThrows(SQLTimeoutException.class,()->storage.create(FRESH,command->{
            sql.add(command);if(sql.size()==2){assertTrue(storage.userAttempted);throw failure;}
        },"FREEPDB1",FILE,PASSWORD)));
        assertEquals(2,sql.size());assertTrue(storage.pending());sql.clear();
        storage.drop(sql::add);storage.drop(sql::add);
        assertEquals(List.of("DROP USER "+NAME+" CASCADE",
                "DROP TABLESPACE "+NAME+" INCLUDING CONTENTS AND DATAFILES"),sql);
        assertFalse(storage.pending());
    }

    @Test
    void timeoutRemainsPrimaryWithBothSanitizedCleanupFailures(){
        var primary=new SQLTimeoutException("private create timeout "+PASSWORD,"HYT00");
        var cleanup=new SQLException("private user cleanup "+PASSWORD,"42000",1940);
        cleanup.addSuppressed(new SQLException("private file cleanup "+PASSWORD,"42000",1110));
        primary.addSuppressed(cleanup);
        var safe=GeneratedInitialSqlSandbox.safe(primary);
        assertEquals("HYT00",safe.getSQLState());assertEquals(0,safe.getErrorCode());
        assertEquals(1,safe.getSuppressed().length);
        var first=assertInstanceOf(SQLException.class,safe.getSuppressed()[0]);assertEquals(1940,first.getErrorCode());
        assertEquals(1,first.getSuppressed().length);
        var second=assertInstanceOf(SQLException.class,first.getSuppressed()[0]);assertEquals(1110,second.getErrorCode());
        for(Throwable failure:new Throwable[]{safe,first,second}){assertNull(failure.getCause());assertFalse(failure.toString().contains(PASSWORD));}
    }

    private static GeneratedInitialSqlSandbox.OracleStorage allocated()throws Exception{
        var storage=new GeneratedInitialSqlSandbox.OracleStorage(NAME);
        storage.create(FRESH,sql->{},"FREEPDB1",FILE,PASSWORD);return storage;
    }
}

package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class InitialSqlSandboxMssqlBoundsTest {
    private static final String DATABASE="cachedb_v019_initial_0123abcd";
    private static final String DIRECTORY="/var/opt/mssql/data/";
    private static final InitialSqlSandbox.MssqlModelFiles MODEL=
            new InitialSqlSandbox.MssqlModelFiles(2,1,1,1024,1024);

    @Test
    void createsOnlyOwnedFilesWithFiniteBoundsInInitialDdl(){
        assertEquals("CREATE DATABASE ["+DATABASE+"] ON PRIMARY (NAME=N'"+DATABASE+"', FILENAME=N'"+
                DIRECTORY+DATABASE+".mdf', SIZE=8MB, MAXSIZE=64MB, FILEGROWTH=8MB) LOG ON (NAME=N'"+
                DATABASE+"_log', FILENAME=N'"+DIRECTORY+DATABASE+
                "_log.ldf', SIZE=8MB, MAXSIZE=32MB, FILEGROWTH=8MB)",
                InitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,DIRECTORY,MODEL));
    }

    @Test
    void acceptsOnlyTheOptionalTrailingDirectorySeparator(){
        assertEquals(InitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,DIRECTORY,MODEL),
                InitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,"/var/opt/mssql/data","/var/opt/mssql/data",MODEL));
        assertDoesNotThrow(()->InitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,DIRECTORY,
                new InitialSqlSandbox.MssqlModelFiles(2,1,1,1,1)));
    }

    @Test
    void rejectsNamesOutsideTheOwnedUuidNamespace(){
        for(String name:new String[]{null,"","master","model","cachedb_v019_initial_0123abc",
                "cachedb_v019_initial_0123abcde","cachedb_v019_initial_0123abcg",
                "cachedb_v019_initial_0123ABCD","cachedb_v019_initial_0123abcd'","../0123abcd"}){
            assertThrows(IllegalStateException.class,
                    ()->InitialSqlSandbox.mssqlCreateDatabaseSql(name,DIRECTORY,DIRECTORY,MODEL));
        }
    }

    @Test
    void rejectsUntrustedDataAndLogDirectories(){
        for(String directory:new String[]{null,"","/","/tmp/","/var/opt/mssql/log/",
                "var/opt/mssql/data/","/var/opt/mssql/data/../data/","/var/opt/mssql//data/",
                " /var/opt/mssql/data/","/var/opt/mssql/data/ ","/var/opt/mssql/data/'",
                "/var/opt/mssql/data/\n","/var/opt/mssql/data/\r","/var/opt/mssql/data/\0",
                "C:\\data\\","\\\\server\\share\\","file:///var/opt/mssql/data/"}){
            assertThrows(IllegalStateException.class,
                    ()->InitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,directory,DIRECTORY,MODEL));
            assertThrows(IllegalStateException.class,
                    ()->InitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,directory,MODEL));
        }
    }

    @Test
    void rejectsMissingAdditionalOrAmbiguousModelFiles(){
        for(InitialSqlSandbox.MssqlModelFiles model:new InitialSqlSandbox.MssqlModelFiles[]{null,
                new InitialSqlSandbox.MssqlModelFiles(0,0,0,0,0),
                new InitialSqlSandbox.MssqlModelFiles(1,1,0,1024,0),
                new InitialSqlSandbox.MssqlModelFiles(3,2,1,1024,1024),
                new InitialSqlSandbox.MssqlModelFiles(3,1,2,1024,1024),
                new InitialSqlSandbox.MssqlModelFiles(3,1,1,1024,1024),
                new InitialSqlSandbox.MssqlModelFiles(2,2,0,1024,1024)}){
            assertThrows(IllegalStateException.class,
                    ()->InitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,DIRECTORY,model));
        }
    }

    @Test
    void rejectsModelSizesThatCannotFitTheInitialAllocation(){
        for(long pages:new long[]{Long.MIN_VALUE,-1,0,1025,Long.MAX_VALUE}){
            assertThrows(IllegalStateException.class,
                    ()->InitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,DIRECTORY,
                            new InitialSqlSandbox.MssqlModelFiles(2,1,1,pages,1024)));
            assertThrows(IllegalStateException.class,
                    ()->InitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,DIRECTORY,
                            new InitialSqlSandbox.MssqlModelFiles(2,1,1,1024,pages)));
        }
    }
}

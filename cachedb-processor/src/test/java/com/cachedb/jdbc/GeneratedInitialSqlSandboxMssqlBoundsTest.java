package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GeneratedInitialSqlSandboxMssqlBoundsTest {
    private static final String DATABASE="cachedb_v019_initial_0123abcd";
    private static final String DIRECTORY="/var/opt/mssql/data/";
    private static final GeneratedInitialSqlSandbox.MssqlModelFiles MODEL=
            new GeneratedInitialSqlSandbox.MssqlModelFiles(2,1,1,1024,1024);

    @Test
    void createsOnlyOwnedFilesWithFiniteBoundsInInitialDdl(){
        assertEquals("CREATE DATABASE ["+DATABASE+"] ON PRIMARY (NAME=N'"+DATABASE+"', FILENAME=N'"+
                DIRECTORY+DATABASE+".mdf', SIZE=8MB, MAXSIZE=64MB, FILEGROWTH=8MB) LOG ON (NAME=N'"+
                DATABASE+"_log', FILENAME=N'"+DIRECTORY+DATABASE+
                "_log.ldf', SIZE=8MB, MAXSIZE=32MB, FILEGROWTH=8MB)",
                GeneratedInitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,DIRECTORY,MODEL));
    }

    @Test
    void acceptsOnlyTheOptionalTrailingDirectorySeparator(){
        assertEquals(GeneratedInitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,DIRECTORY,MODEL),
                GeneratedInitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,"/var/opt/mssql/data","/var/opt/mssql/data",MODEL));
        assertDoesNotThrow(()->GeneratedInitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,DIRECTORY,
                new GeneratedInitialSqlSandbox.MssqlModelFiles(2,1,1,1,1)));
    }

    @Test
    void rejectsNamesOutsideTheOwnedUuidNamespace(){
        for(String name:new String[]{null,"","master","model","cachedb_v019_initial_0123abc",
                "cachedb_v019_initial_0123abcde","cachedb_v019_initial_0123abcg",
                "cachedb_v019_initial_0123ABCD","cachedb_v019_initial_0123abcd'","../0123abcd"}){
            assertThrows(IllegalStateException.class,
                    ()->GeneratedInitialSqlSandbox.mssqlCreateDatabaseSql(name,DIRECTORY,DIRECTORY,MODEL));
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
                    ()->GeneratedInitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,directory,DIRECTORY,MODEL));
            assertThrows(IllegalStateException.class,
                    ()->GeneratedInitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,directory,MODEL));
        }
    }

    @Test
    void rejectsMissingAdditionalOrAmbiguousModelFiles(){
        for(GeneratedInitialSqlSandbox.MssqlModelFiles model:new GeneratedInitialSqlSandbox.MssqlModelFiles[]{null,
                new GeneratedInitialSqlSandbox.MssqlModelFiles(0,0,0,0,0),
                new GeneratedInitialSqlSandbox.MssqlModelFiles(1,1,0,1024,0),
                new GeneratedInitialSqlSandbox.MssqlModelFiles(3,2,1,1024,1024),
                new GeneratedInitialSqlSandbox.MssqlModelFiles(3,1,2,1024,1024),
                new GeneratedInitialSqlSandbox.MssqlModelFiles(3,1,1,1024,1024),
                new GeneratedInitialSqlSandbox.MssqlModelFiles(2,2,0,1024,1024)}){
            assertThrows(IllegalStateException.class,
                    ()->GeneratedInitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,DIRECTORY,model));
        }
    }

    @Test
    void rejectsModelSizesThatCannotFitTheInitialAllocation(){
        for(long pages:new long[]{Long.MIN_VALUE,-1,0,1025,Long.MAX_VALUE}){
            assertThrows(IllegalStateException.class,
                    ()->GeneratedInitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,DIRECTORY,
                            new GeneratedInitialSqlSandbox.MssqlModelFiles(2,1,1,pages,1024)));
            assertThrows(IllegalStateException.class,
                    ()->GeneratedInitialSqlSandbox.mssqlCreateDatabaseSql(DATABASE,DIRECTORY,DIRECTORY,
                            new GeneratedInitialSqlSandbox.MssqlModelFiles(2,1,1,1024,pages)));
        }
    }
}

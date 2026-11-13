package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.*;
import com.zaxxer.hikari.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Explicit disposable infrastructure only. Never selects, deletes or drops shared CacheDB fence data. */
final class GeneratedInitialSqlSandbox implements AutoCloseable {
    final String provider;
    final String name="cachedb_v019_initial_"+UUID.randomUUID().toString().replace("-","").substring(0,8);
    final JdbcTenantPurgeDialect dialect;
    final HikariDataSource admin;
    HikariDataSource source;
    boolean created;
    OracleStorage oracleStorage;

    GeneratedInitialSqlSandbox(String provider) throws Exception {
        this.provider=provider;dialect=JdbcTenantPurgeDialect.valueOf(provider.toUpperCase(Locale.ROOT));
        requireOwned();
        String password=setting(provider,"PASSWORD",null);
        String url=setting(provider,"URL",null);
        String user=setting(provider,"USER",provider.equals("oracle")?"system":null);
        admin=pool(url,user,password,null,null);
        try {
            try(var c=admin.getConnection()){
                if(provider.equals("postgres")){
                    ddl(c,"CREATE SCHEMA "+name);created=true;
                }else if(provider.equals("mssql")){
                    ddl(c,mssqlCreateDatabaseSql(c,name));created=true;
                }else{
                    oracleStorage=new OracleStorage(name);
                    try(var query=c.createStatement()){
                        query.setQueryTimeout(10);query.setMaxRows(2);
                        try(var files=query.executeQuery("SELECT SYS_CONTEXT('USERENV','CON_NAME'),file_name FROM dba_data_files WHERE tablespace_name='USERS'")){
                            if(!files.next())throw new IllegalStateException("Oracle fixture USERS file metadata missing");
                            String container=files.getString(1),file=files.getString(2);
                            if(files.next())throw new IllegalStateException("Oracle fixture USERS file metadata ambiguous");
                            oracleStorage.create((owner,target)->verifyOracleFresh(c,owner,target),sql->ddl(c,sql),container,file,password);
                        }
                    }
                }
            }
            // Retain the configured endpoint; setCatalog must succeed before any owned DDL is run.
            source=pool(url,provider.equals("oracle")?name:user,password,
                    provider.equals("postgres")?name:null,provider.equals("mssql")?name:null);
            try(var c=source.getConnection()){
                assertScope(c);install(c);assertSchema(c);
            }
            System.out.println("INITIAL_SQL_FIXTURE "+provider+" "+name+" migrations=V014,V019,V019_1,V019_2,V019_3,V019_4,V019_5,V019_6");
        }catch(Exception|Error failure){
            try{close();}catch(Exception|Error cleanup){failure.addSuppressed(safe(cleanup));}
            if(failure instanceof SQLException sql)throw safe(sql);
            if(failure instanceof Error fatal)throw fatal;
            throw (Exception)failure;
        }
    }
    private static HikariDataSource pool(String url,String user,String password,String schema,String catalog){
        var cfg=new HikariConfig();cfg.setJdbcUrl(url);cfg.setUsername(user);cfg.setPassword(password);
        if(schema!=null)cfg.setSchema(schema);
        if(catalog!=null)cfg.setCatalog(catalog);
        cfg.setMaximumPoolSize(2);cfg.setMinimumIdle(0);cfg.setConnectionTimeout(10000);
        return new HikariDataSource(cfg);
    }
    static String setting(String provider,String suffix,String adminDefault){
        String value=System.getenv("CACHEDB_INITIAL_"+provider.toUpperCase(Locale.ROOT)+"_ADMIN_"+suffix);
        if(value==null||value.isEmpty())value=adminDefault!=null?adminDefault:
                System.getenv("CACHEDB_SNAPSHOT_"+provider.toUpperCase(Locale.ROOT)+"_"+suffix);
        if(value==null||value.isEmpty()||value.length()>4096)
            throw new IllegalStateException("Explicit disposable INITIAL SQL fixture setting required: "+suffix);
        return value;
    }
    void assertScope(Connection c)throws SQLException{
        requireOwned();
        if(provider.equals("mssql"))assertEquals(name,c.getCatalog(),"Owned SQL catalog required");
        else assertEquals(name,Objects.toString(c.getSchema(),"").toLowerCase(Locale.ROOT),"Owned SQL schema required");
    }
    private void install(Connection c)throws Exception{
        for(String version:List.of("V014__durable_write_fence.sql","V019__tenant_protection.sql",
                "V019_1__purge_facade.sql","V019_2__purge_list_scan_kind.sql","V019_3__source_owner_onboarding.sql",
                "V019_4__initial_source_bootstrap.sql","V019_5__namespace_bootstrap_once.sql","V019_6__empty_tenant_activation.sql")){
            String resource="/com/reactor/cachedb/jdbc/migrations/"+provider+"/"+version;
            byte[] bytes;
            try(var in=GeneratedInitialSqlSandbox.class.getResourceAsStream(resource)){
                if(in==null)throw new AssertionError("Mandatory isolated migration missing: "+version);
                bytes=in.readNBytes(131073);if(bytes.length>131072)throw new AssertionError("Migration size bound");
            }
            String sql=new String(bytes,StandardCharsets.UTF_8).lines().filter(line->!line.stripLeading().startsWith("--"))
                    .collect(java.util.stream.Collectors.joining("\n"));
            for(String statement:sql.split(";")){
                String command=statement.strip();if(command.isEmpty())continue;
                if(!command.matches("(?is)^(CREATE TABLE|CREATE INDEX|ALTER TABLE) .*"))
                    throw new AssertionError("Unexpected private migration command");
                ddl(c,command);
            }
        }
    }
    private void assertSchema(Connection c)throws SQLException{
        var expected=Set.of("cachedb_write_fence","cachedb_tenant_guard","cachedb_purge_progress","cachedb_purge_scan",
                "cachedb_purge_work_budget","cachedb_purge_work","cachedb_tenant_allocator","cachedb_purge_participant",
                "cachedb_purge_facade","cachedb_purge_call","cachedb_owner_onboarding","cachedb_initial_bootstrap",
                "cachedb_initial_page","cachedb_namespace_bootstrap","cachedb_empty_activation");
        var found=new HashSet<String>();
        try(var r=c.getMetaData().getTables(c.getCatalog(),c.getSchema(),null,new String[]{"TABLE"})){
            while(r.next())found.add(r.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        assertTrue(found.containsAll(expected),"Mandatory isolated metadata absent");
        // LIST upgrade is real, not a schema-version label.
        try(var q=c.prepareStatement("SELECT COUNT(*) FROM cachedb_purge_scan WHERE scan_kind='LIST'");var r=q.executeQuery()){
            assertTrue(r.next());assertEquals(0,r.getInt(1));
        }
    }
    private static String mssqlCreateDatabaseSql(Connection connection,String database)throws SQLException{
        String query="""
                SELECT CONVERT(nvarchar(4000), SERVERPROPERTY('InstanceDefaultDataPath')),
                       CONVERT(nvarchar(4000), SERVERPROPERTY('InstanceDefaultLogPath')),
                       COUNT_BIG(*),
                       SUM(CASE WHEN type = 0 THEN CONVERT(bigint, 1) ELSE 0 END),
                       SUM(CASE WHEN type = 1 THEN CONVERT(bigint, 1) ELSE 0 END),
                       MAX(CASE WHEN type = 0 THEN CONVERT(bigint, size) END),
                       MAX(CASE WHEN type = 1 THEN CONVERT(bigint, size) END)
                FROM sys.master_files
                WHERE database_id = DB_ID(N'model')
                """;
        try(var statement=connection.createStatement()){
            statement.setQueryTimeout(10);
            try(var rows=statement.executeQuery(query)){
                if(!rows.next())throw new IllegalStateException("SQL Server fixture preflight metadata missing");
                String sql=mssqlCreateDatabaseSql(database,rows.getString(1),rows.getString(2),
                        new MssqlModelFiles(rows.getLong(3),rows.getLong(4),rows.getLong(5),
                                rows.getLong(6),rows.getLong(7)));
                if(rows.next())throw new IllegalStateException("SQL Server fixture preflight metadata ambiguous");
                return sql;
            }
        }
    }
    record MssqlModelFiles(long fileCount,long dataCount,long logCount,long dataPages,long logPages){}

    static String mssqlCreateDatabaseSql(String database,String dataDirectory,String logDirectory,MssqlModelFiles model){
        if(database==null||!database.matches("cachedb_v019_initial_[0-9a-f]{8}"))
            throw new IllegalStateException("Private fixture name rejected");
        String dataPath=mssqlFixtureDirectory(dataDirectory);
        String logPath=mssqlFixtureDirectory(logDirectory);
        if(model==null||model.fileCount()!=2||model.dataCount()!=1||model.logCount()!=1)
            throw new IllegalStateException("SQL Server model file layout is not supported by the bounded fixture");
        // SQL Server reports 8 KiB pages; reject inherited model sizes above the fixed 8 MiB allocation.
        if(model.dataPages()<=0||model.dataPages()>1024||model.logPages()<=0||model.logPages()>1024)
            throw new IllegalStateException("SQL Server model files do not fit the fixed 8 MiB fixture allocation");
        return "CREATE DATABASE ["+database+"] ON PRIMARY (NAME=N'"+database+"', FILENAME=N'"+
                dataPath+database+".mdf', SIZE=8MB, MAXSIZE=64MB, FILEGROWTH=8MB) LOG ON (NAME=N'"+
                database+"_log', FILENAME=N'"+logPath+database+
                "_log.ldf', SIZE=8MB, MAXSIZE=32MB, FILEGROWTH=8MB)";
    }
    private static String mssqlFixtureDirectory(String directory){
        // This disposable Linux fixture must not place files outside its known SQL Server data volume.
        if(!"/var/opt/mssql/data".equals(directory)&&!"/var/opt/mssql/data/".equals(directory))
            throw new IllegalStateException("SQL Server fixture default directory is not the dedicated data volume");
        return "/var/opt/mssql/data/";
    }
    private static void ddl(Connection c,String sql)throws SQLException{
        try(var q=c.createStatement()){q.setQueryTimeout(15);q.executeUpdate(sql);}
        catch(SQLException failure){throw safe(failure);}
    }
    @FunctionalInterface
    interface OracleDdl { void execute(String sql)throws SQLException; }
    @FunctionalInterface
    interface OracleFreshness { void verify(String owner,String file)throws SQLException; }

    private static void verifyOracleFresh(Connection connection,String owner,String file)throws SQLException{
        String sql="SELECT (SELECT COUNT(*) FROM dba_users WHERE username=?)"
                +"+(SELECT COUNT(*) FROM dba_roles WHERE role=?)"
                +",(SELECT COUNT(*) FROM dba_tablespaces WHERE tablespace_name=?)"
                +",(SELECT COUNT(*) FROM dba_data_files WHERE file_name=?)"
                +"+(SELECT COUNT(*) FROM dba_temp_files WHERE file_name=?) FROM dual";
        try(var query=connection.prepareStatement(sql)){
            query.setQueryTimeout(10);query.setMaxRows(2);
            query.setString(1,owner);query.setString(2,owner);query.setString(3,owner);
            query.setString(4,file);query.setString(5,file);
            try(var rows=query.executeQuery()){
                if(!rows.next())throw new IllegalStateException("Oracle fixture freshness metadata missing");
                OracleStorage.requireFresh(rows.getLong(1),rows.getLong(2),rows.getLong(3));
                if(rows.next())throw new IllegalStateException("Oracle fixture freshness metadata ambiguous");
            }
        }
    }

    /** Owns only one fresh user and its removable datafile; never resizes shared Oracle files. */
    static final class OracleStorage {
        final String name;
        boolean tablespaceAttempted,userAttempted;
        OracleStorage(String name){
            if(name==null||!name.matches("cachedb_v019_initial_[0-9a-f]{8}"))
                throw new IllegalStateException("Private fixture name rejected");
            this.name=name;
        }
        void create(OracleFreshness freshness,OracleDdl ddl,String container,String usersFile,String password)throws SQLException{
            if(tablespaceAttempted||userAttempted)throw new IllegalStateException("Oracle fixture already allocated");
            if(password==null||password.isEmpty()||password.length()>128||password.indexOf('\0')>=0
                    ||password.indexOf('\n')>=0||password.indexOf('\r')>=0)
                throw new IllegalArgumentException("Invalid private fixture credential");
            if(container==null||container.isBlank()||container.equalsIgnoreCase("CDB$ROOT")||container.equalsIgnoreCase("PDB$SEED"))
                throw new IllegalStateException("Oracle fixture requires a writable application PDB");
            // Use only the observed USERS directory, without REUSE or a caller-supplied file path.
            if(usersFile==null||!usersFile.matches("/[A-Za-z0-9_./-]+\\.dbf")||usersFile.contains("//")
                    ||usersFile.contains("/../")||usersFile.contains("/./")||usersFile.lastIndexOf('/')==0)
                throw new IllegalStateException("Oracle fixture requires an unambiguous absolute filesystem datafile");
            String file=usersFile.substring(0,usersFile.lastIndexOf('/')+1)+name+".dbf";
            freshness.verify(name.toUpperCase(Locale.ROOT),file);
            // A timeout can follow committed DDL. Only explicit collision responses disown an attempt.
            tablespaceAttempted=true;
            try{
                ddl.execute("CREATE SMALLFILE TABLESPACE "+name+" DATAFILE '"+file
                        +"' SIZE 8M AUTOEXTEND ON NEXT 8M MAXSIZE 64M EXTENT MANAGEMENT LOCAL SEGMENT SPACE MANAGEMENT AUTO");
            }catch(SQLException failure){
                if(failure.getErrorCode()==955||failure.getErrorCode()==1543)tablespaceAttempted=false;
                throw failure;
            }
            userAttempted=true;
            try{
                ddl.execute("CREATE USER "+name+" IDENTIFIED BY \""+password.replace("\"","\"\"")
                        +"\" DEFAULT TABLESPACE "+name+" QUOTA 64M ON "+name);
            }catch(SQLException failure){
                if(failure.getErrorCode()==955||failure.getErrorCode()==1920)userAttempted=false;
                throw failure;
            }
            ddl.execute("GRANT CREATE SESSION,CREATE TABLE TO "+name);
        }
        static void requireFresh(long principals,long tablespaces,long files){
            if(principals!=0||tablespaces!=0||files!=0)
                throw new IllegalStateException("Oracle fixture owner, tablespace and file must all be fresh");
        }
        boolean pending(){return userAttempted||tablespaceAttempted;}
        void drop(OracleDdl ddl)throws SQLException{
            SQLException failure=null;
            if(userAttempted){
                try{ddl.execute("DROP USER "+name+" CASCADE");userAttempted=false;}
                catch(SQLException problem){if(problem.getErrorCode()==1918)userAttempted=false;else failure=problem;}
            }
            if(tablespaceAttempted){
                try{ddl.execute("DROP TABLESPACE "+name+" INCLUDING CONTENTS AND DATAFILES");tablespaceAttempted=false;}
                catch(SQLException problem){
                    if(problem.getErrorCode()==959)tablespaceAttempted=false;
                    else if(failure==null)failure=problem;else failure.addSuppressed(problem);
                }
            }
            if(failure!=null)throw failure;
        }
    }

    private void closeOracle()throws Exception{
        boolean owned=oracleStorage.pending();
        Throwable failure=null;
        try{if(source!=null)source.close();}catch(Exception|Error problem){failure=problem;}
        try{
            if(oracleStorage.pending())try(var connection=admin.getConnection()){
                oracleStorage.drop(sql->ddl(connection,sql));
            }
        }catch(Exception|Error problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        try{admin.close();}catch(Exception|Error problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        if(failure instanceof Error fatal)throw fatal;
        if(failure!=null)throw (Exception)failure;
        if(owned)System.out.println("INITIAL_SQL_FIXTURE_REMOVED "+provider+" "+name);
    }
    private void requireOwned(){
        if(!name.matches("cachedb_v019_initial_[0-9a-f]{8}"))throw new IllegalStateException("Private fixture name rejected");
    }
    static void closeFixture(boolean callerOwnedSql,AutoCloseable sandbox,AutoCloseable... dependents)throws Exception{
        try(AutoCloseable sqlOwner=callerOwnedSql?null:sandbox){
            Throwable failure=null;
            for(var dependent:dependents){
                if(dependent==null)continue;
                try{dependent.close();}
                catch(Exception|Error problem){
                    if(failure==null)failure=problem;
                    else if(failure!=problem)failure.addSuppressed(problem);
                }
            }
            if(failure instanceof Error fatal)throw fatal;
            if(failure!=null)throw (Exception)failure;
        }
    }
    static void closeAfterFailedConstruction(Throwable primary,boolean callerOwnedSql,
            AutoCloseable sandbox,AutoCloseable... dependents){
        try{closeFixture(callerOwnedSql,sandbox,dependents);}
        catch(Exception|Error cleanup){if(primary!=cleanup)primary.addSuppressed(cleanup);}
    }
    static void closeSandbox(AutoCloseable source,AutoCloseable ownedScope,AutoCloseable admin)throws Exception{
        // Reverse resource order keeps admin available for DROP, even when source closure fails.
        try(admin;ownedScope;source){}
    }
    public void close()throws Exception{
        requireOwned();
        if(oracleStorage!=null){closeOracle();return;}
        closeSandbox(source,this::dropCreatedScope,admin);
    }
    private void dropCreatedScope()throws SQLException{
        if(created)try(var c=admin.getConnection()){
            // Only the single exact name allocated by this object is ever targeted.
            if(provider.equals("postgres"))ddl(c,"DROP SCHEMA "+name+" CASCADE");
            else if(provider.equals("mssql")){
                ddl(c,"ALTER DATABASE ["+name+"] SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
                ddl(c,"DROP DATABASE ["+name+"]");
            }else ddl(c,"DROP USER "+name+" CASCADE");
            created=false;System.out.println("INITIAL_SQL_FIXTURE_REMOVED "+provider+" "+name);
        }
    }
    static SQLException safe(SQLException failure){
        var sanitized=new SQLException("Private SQL fixture failure [state="+failure.getSQLState()+",code="+failure.getErrorCode()+"]",
                failure.getSQLState(),failure.getErrorCode());
        for(Throwable suppressed:failure.getSuppressed())sanitized.addSuppressed(safe(suppressed));
        return sanitized;
    }
    private static Throwable safe(Throwable failure){
        return failure instanceof SQLException sql?safe(sql):new IllegalStateException("Private fixture cleanup failed");
    }
}

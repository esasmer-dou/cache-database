package com.reactor.cachedb.starter;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.*;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import com.reactor.cachedb.mssql.MssqlWriteBehindFlusher;
import com.reactor.cachedb.oracle.OracleWriteBehindFlusher;
import com.reactor.cachedb.postgres.PostgresWriteBehindFlusher;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import redis.clients.jedis.StreamEntryID;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@Timeout(90)
class RedisBulkDeleteProvidersTest {
    private static String setting(String key){
        String value=System.getProperty(key);
        if(value==null||value.isBlank())value=System.getenv(key.replace('.','_').replace('-','_').toUpperCase(Locale.ROOT));
        if(value==null||value.isBlank())throw new IllegalStateException("Required disposable provider fixture setting is missing");
        return value;
    }
    static final class Source implements DataSource {
        final String url,user,password;
        Source(String provider){
            url=setting("cachedb.snapshot."+provider+".url");user=setting("cachedb.snapshot."+provider+".user");
            password=setting("cachedb.snapshot."+provider+".password");
        }
        public Connection getConnection()throws SQLException{return DriverManager.getConnection(url,user,password);}
        public Connection getConnection(String user,String password)throws SQLException{return DriverManager.getConnection(url,user,password);}
        public java.io.PrintWriter getLogWriter(){return null;}
        public void setLogWriter(java.io.PrintWriter writer){}
        public void setLoginTimeout(int seconds){if(seconds!=0)throw new UnsupportedOperationException();}
        public int getLoginTimeout(){return 0;}
        public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("Not a wrapper");}
        public boolean isWrapperFor(Class<?> type){return false;}
    }
    @ParameterizedTest @ValueSource(strings={"postgres","mssql","oracle"})
    void redisReceiptsQueueRealProviderDeletesWithoutAnyForegroundOwnershipSql(String provider)throws Exception{
        var sql=new Source(provider);
        String table="f2b_"+UUID.randomUUID().toString().replace("-","").substring(0,18);
        try(var connection=sql.getConnection();var statement=connection.createStatement()){
            statement.execute("CREATE TABLE "+table+" (id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(256), value_text VARCHAR(200), entity_version "
                    +(provider.equals("oracle")?"NUMBER(19)":"BIGINT")+")");
        }
        try{
            var registry=new DefaultEntityRegistry(ResourceLimits.defaults());
            var metadata=RedisBulkDeleteRedisTest.metadata(table);
            registry.register(metadata,RedisBulkDeleteRedisTest.CODEC,CachePolicy.defaults(),
                    new com.reactor.cachedb.core.relation.NoOpRelationBatchLoader<>(),
                    new com.reactor.cachedb.core.page.NoOpEntityPageLoader<>());
            WriteBehindFlusher flusher=switch(provider){
                case "postgres"->new PostgresWriteBehindFlusher(sql,registry);
                case "mssql"->new MssqlWriteBehindFlusher(sql,registry);
                default->new OracleWriteBehindFlusher(sql,registry);
            };
            var initial=new ArrayList<QueuedWriteOperation>();
            for(int i=1;i<=3;i++){
                String id=Integer.toString(i);
                initial.add(new QueuedWriteOperation(OperationType.UPSERT,metadata.entityName(),table,"f2bulk","",
                        "id","entity_version",null,id,Map.of("id",id,"tenant_id","a","value_text","value-"+id,"entity_version","1"),1,Instant.now()));
            }
            flusher.flushBatch(initial);
            try(var f=new RedisBulkDeleteRedisTest.Fixture(RedisBulkDeleteRedisTest.standalone(),false,sql,table);
                    var tenant=CacheTenantContext.withTenant("a")){
                f.seed(3);int before=f.source.connections.get();
                var receipts=f.repository.bulkDelete(f.ids(3));
                assertEquals(3,receipts.size());assertEquals(before,f.source.connections.get());
                assertEquals(0,f.ownerSourceCalls.get());assertEquals(1,f.client.bulkCalls.get());
                assertEquals(3,rows(sql,table),"Redis acceptance alone must not claim foreground SQL durability");
                var queued=new ArrayList<QueuedWriteOperation>();
                var mapper=new com.reactor.cachedb.redis.RedisWriteOperationMapper();
                for(var entry:f.client.commands().xrange(f.prefix+":writes",new StreamEntryID("0-0"),new StreamEntryID("9999999999999-0"),20)){
                    if("DELETE".equals(entry.getFields().get("type")))queued.add(mapper.fromBody(entry.getFields()));
                }
                assertEquals(3,queued.size(),"Use actual Redis write-behind entries, not synthetic receipt-derived operations");
                for(var receipt:receipts)assertTrue(queued.stream().anyMatch(op->op.id().equals(receipt.id())&&op.version()==receipt.version()));
                flusher.flushBatch(queued);
                assertEquals(0,rows(sql,table));
            }
        }finally{
            try(var connection=sql.getConnection();var statement=connection.createStatement()){statement.execute("DROP TABLE "+table);}
        }
    }
    private static long rows(DataSource source,String table)throws SQLException{
        try(var connection=source.getConnection();var statement=connection.createStatement();var result=statement.executeQuery("SELECT COUNT(*) FROM "+table)){
            assertTrue(result.next());return result.getLong(1);
        }
    }
}

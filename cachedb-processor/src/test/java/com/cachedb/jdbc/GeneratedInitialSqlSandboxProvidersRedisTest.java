package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.protection.InitialTenantBootstrap;
import com.reactor.cachedb.core.protection.ProtectionException;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.initial.isolatedSql",matches="true")
@Timeout(120)
class GeneratedInitialSqlSandboxProvidersRedisTest {
    @TempDir Path temp;

    @Test
    void mssqlGeneratedInitialUsesBoundedOwnedFilesAndPreservesTenantIsolation()throws Exception {
        try(var admin=CacheTenantContext.administrative();
                var sqlOwner=new GeneratedInitialSqlSandbox("mssql");
                var f=new GeneratedInitialNativeFixture(temp,false,sqlOwner)) {
            assertSame(sqlOwner,f.sandbox);
            assertMssqlFiles(sqlOwner);
            var progress=f.activate();
            assertEquals(InitialTenantBootstrap.Phase.ACTIVE,progress.phase());
            assertEquals(2,progress.warmedRows());
            assertTrue(progress.highwater()>=9000);
            assertTrue(progress.highwater()<18000);
            var keys=f.database.redisKeyStrategy();
            assertEquals("OPEN",f.redis.hget(f.primary.barrier(f.ENTITY).generationKey(f.generation.key()),"state"));
            for(long id:List.of(1L,3L)) {
                String payload=f.redis.get(keys.entityKey(f.NS,id));
                assertNotNull(payload);
                assertTrue(payload.contains("~E1:k2:"));
                assertFalse(payload.contains(f.SECRET));
                assertEquals(id==1?"7":"8",f.redis.get(keys.versionKey(f.NS,id)));
                assertEquals("a",f.redis.get(keys.versionKey(f.NS,id)+":tenant"));
                String indexes=f.redis.hgetAll(keys.indexMetaKey(f.NS,id)).toString();
                assertFalse(indexes.contains(f.SECRET));
                assertTrue(indexes.contains(f.bound.indexQueryValue("secret",f.SECRET+id).toString()));
            }
            assertTrue(f.originalSource.get(2L).startsWith("b|~E1:k1:"));
            for(long id:List.of(2L,99L)) {
                assertFalse(f.redis.exists(keys.entityKey(f.NS,id)));
                assertFalse(f.redis.exists(keys.versionKey(f.NS,id)));
                assertFalse(f.redis.exists(keys.versionKey(f.NS,id)+":tenant"));
                assertFalse(f.redis.exists(keys.indexMetaKey(f.NS,id)));
            }
            assertEquals(f.originalSource,f.sourceSnapshot());
            int before=f.source.calls.get();
            try(var tracked=f.source.track()) {
                for(long id:List.of(1L,3L))
                    f.primary.deliver(f.metadata,"a",repo->repo.findById(id),row->assertEquals(f.row(id),row.orElseThrow()));
                var failure=assertThrows(ProtectionException.class,
                        ()->f.primary.deliver(f.metadata,"a",repo->repo.findById(2L),row->fail("Foreign row must not deliver")));
                assertEquals(ProtectionException.Code.STORAGE_UNAVAILABLE,failure.code());
                assertNull(failure.getCause());
                assertEquals(0,failure.getSuppressed().length);
                assertFalse(failure.toString().contains(f.SECRET));
            }
            assertEquals(before,f.source.calls.get());
            assertEquals(0,f.source.foreground.get());
            var write=f.database.config().writeBehind();
            for(String key:List.of(write.streamKey(),write.deadLetterStreamKey(),write.compactionStreamKey()))
                assertEquals(0,f.redis.xlen(key));
            f.evidence("ASSERTIONS_COMPLETE provider=mssql generatedRecord=true generatedMetadata=true generatedBoundCodec=true"
                    +" boundedOwnedSqlFiles=true tenantIsolation=true sourceUnchanged=true foregroundSqlBorrows=0 sqlDisposalPending=true");
        }
    }

    private static void assertMssqlFiles(GeneratedInitialSqlSandbox sandbox)throws Exception {
        try(var connection=sandbox.source.getConnection();var statement=connection.createStatement()) {
            sandbox.assertScope(connection);
            statement.setQueryTimeout(5);
            statement.setMaxRows(3);
            try(var files=statement.executeQuery("SELECT type,size,max_size,growth,is_percent_growth,name,physical_name FROM sys.database_files")) {
                var types=new HashSet<Integer>();
                while(files.next()) {
                    int type=files.getInt(1);
                    assertTrue(type==0||type==1);
                    assertTrue(types.add(type));
                    int maximum=type==0?8192:4096;
                    assertTrue(files.getLong(2)>=1024&&files.getLong(2)<=maximum);
                    assertEquals(maximum,files.getLong(3));
                    assertEquals(1024,files.getLong(4));
                    assertFalse(files.getBoolean(5));
                    String suffix=type==0?"":"_log";
                    assertEquals(sandbox.name+suffix,files.getString(6));
                    assertEquals("/var/opt/mssql/data/"+sandbox.name+suffix+(type==0?".mdf":".ldf"),files.getString(7));
                }
                assertEquals(java.util.Set.of(0,1),types);
            }
        }
    }
}

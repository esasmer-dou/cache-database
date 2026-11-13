package com.reactor.cachedb.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.protection.InitialTenantBootstrap;
import com.reactor.cachedb.core.protection.ProtectionException;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named="cachedb.snapshot.providers",matches="true")
@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@EnabledIfSystemProperty(named="cachedb.initial.isolatedSql",matches="true")
@Timeout(120)
class GeneratedInitialNativeRedisTest {
    @TempDir Path temp;
    @Test void generatedEncryptedInitialExcludesAnInWindowForeignTenantWithoutForegroundSql()throws Exception {
        try(var admin=CacheTenantContext.administrative();var f=new GeneratedInitialNativeFixture(temp,false)) {
            var progress=f.activate();assertEquals(InitialTenantBootstrap.Phase.ACTIVE,progress.phase());
            assertEquals(2,progress.warmedRows());assertTrue(progress.highwater()>=9000);assertTrue(progress.highwater()<18000);
            var keys=f.database.redisKeyStrategy();
            assertEquals("OPEN",f.redis.hget(f.primary.barrier(f.ENTITY).generationKey(f.generation.key()),"state"));
            for(long id:List.of(1L,3L)) {
                String raw=f.redis.get(keys.entityKey(f.NS,id));assertNotNull(raw);
                assertTrue(raw.contains("~E1:k2:"));assertFalse(raw.contains(f.SECRET));
                assertEquals(id==1?"7":"8",f.redis.get(keys.versionKey(f.NS,id)));
                assertEquals("a",f.redis.get(keys.versionKey(f.NS,id)+":tenant"));
                String indexes=f.redis.hgetAll(keys.indexMetaKey(f.NS,id)).toString();
                assertFalse(indexes.contains(f.SECRET));assertTrue(indexes.contains(f.bound.indexQueryValue("secret",f.SECRET+id).toString()));
            }
            // Foreign2 is BETWEEN selected1 and3 and has a greater source version than tenant a.
            assertTrue(f.originalSource.get(2L).startsWith("b|~E1:k1:"));
            for(long id:List.of(2L,99L)) {
                assertFalse(f.redis.exists(keys.entityKey(f.NS,id)));assertFalse(f.redis.exists(keys.versionKey(f.NS,id)+":tenant"));
                assertFalse(f.redis.exists(keys.indexMetaKey(f.NS,id)));
            }
            assertEquals(f.originalSource,f.sourceSnapshot());int before=f.source.calls.get();
            try(var tracked=f.source.track()) {
                for(long id:List.of(1L,3L))f.primary.deliver(f.metadata,"a",repo->repo.findById(id),row->assertEquals(f.row(id),row.orElseThrow()));
                var failure=assertThrows(ProtectionException.class,()->f.primary.deliver(f.metadata,"a",repo->repo.findById(2L),row->fail("Unknown owner must never deliver")));
                assertEquals(ProtectionException.Code.STORAGE_UNAVAILABLE,failure.code());assertNull(failure.getCause());
                assertEquals(0,failure.getSuppressed().length);assertFalse(failure.toString().contains(f.SECRET));
            }
            assertEquals(before,f.source.calls.get());assertEquals(0,f.source.foreground.get());
            var write=f.database.config().writeBehind();
            for(String key:List.of(write.streamKey(),write.deadLetterStreamKey(),write.compactionStreamKey()))assertEquals(0,f.redis.xlen(key));
            f.evidence("PASS generatedRecord=true generatedMetadata=true generatedBoundCodec=true range=1..3 selected=1,3 inWindowForeign=2 cold=99"
                    +" foreignVersionExcluded=true oldSourceKey=k1 currentRedisKey=k2 blindIndex=true sourceUnchanged=true foregroundSqlBorrows=0");
        }
    }
}

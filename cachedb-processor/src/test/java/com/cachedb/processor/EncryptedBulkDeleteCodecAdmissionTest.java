package com.reactor.cachedb.processor;

import static com.reactor.cachedb.processor.EncryptionCompilerFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.config.ResourceLimits;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.registry.DefaultEntityRegistry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedBulkDeleteCodecAdmissionTest {
    @TempDir Path temp;
    @Test void missingOrFailingKeyProviderCannotCreateTheRequiredBoundRegistration()throws Exception{
        try(var f=EncryptedBulkDeleteRedisTest.generated(temp)){
            var absent=new DefaultEntityRegistry(ResourceLimits.defaults());
            EncryptedBulkDeleteRedisTest.safe(assertThrows(CacheEncryptionException.class,
                    ()->absent.register(f.metadata(),f.unbound(),CachePolicy.defaults(),null,null)));
            assertTrue(absent.all().isEmpty());
            var failing=new CacheKeyProvider(){
                public KeyMaterial current(String ring){throw new IllegalStateException(SECRET);}
                public Optional<KeyMaterial> byId(String ring,String id){throw new IllegalStateException(HIDDEN);}
            };
            var registry=new DefaultEntityRegistry(ResourceLimits.defaults(),failing);
            var failure=assertThrows(CacheEncryptionException.class,()->registry.register(f.metadata(),f.unbound(),CachePolicy.defaults(),null,null));
            EncryptedBulkDeleteRedisTest.safe(failure);assertTrue(registry.all().isEmpty());
        }
    }
    @Test void generatedQ1IdentityDecoderRejectsNonCanonicalIdsWithoutProtectedFieldInput()throws Exception{
        try(var f=EncryptedBulkDeleteRedisTest.generated(temp)){
            var provider=new EncryptedBulkDeleteRedisTest.CountingKeys();var codec=f.unbound().bindEncryption(provider);
            int before=provider.lookups.get();
            var q=Map.of(EncryptedCodecSupport.QUEUE_FORMAT,EncryptedCodecSupport.QUEUE_VERSION,
                    EncryptedCodecSupport.QUEUE_TENANT,"~T1:tenant-a",f.metadata().idColumn(),"1");
            assertEquals(Map.of(f.metadata().idColumn(),"1"),codec.sourceColumnsFromQueued("1",q));
            assertEquals(before,provider.lookups.get());
            EncryptedBulkDeleteRedisTest.safe(assertThrows(CacheEncryptionException.class,()->codec.sourceColumnsFromQueued("01",q)));
        }
    }
    @Test void registeredDeleteQueueAdmissionRejectsWrongMappingBeforeAnyJdbcWork()throws Exception{
        try(var f=EncryptedBulkDeleteRedisTest.generated(temp)){
            var registry=new DefaultEntityRegistry(ResourceLimits.defaults(),keys("k1",key("k1",1)));
            registry.register(f.metadata(),f.unbound(),CachePolicy.defaults(),null,null);
            var good=new QueuedWriteOperation(OperationType.DELETE,f.metadata().entityName(),f.metadata().tableName(),f.metadata().redisNamespace(),
                    "",f.metadata().idColumn(),f.metadata().versionColumn(),f.metadata().deletedColumn(),"1",
                    Map.of(f.metadata().idColumn(),"1",f.metadata().versionColumn(),"2"),2,Instant.EPOCH);
            assertEquals(List.of(good),EncryptedQueuedWrites.sourceColumns(registry,List.of(good)));
            var wrong=new QueuedWriteOperation(OperationType.DELETE,good.entityName(),"other_table",good.redisNamespace(),
                    "",good.idColumn(),good.versionColumn(),good.deletedColumn(),good.id(),good.columns(),good.version(),good.createdAt());
            EncryptedBulkDeleteRedisTest.safe(assertThrows(CacheEncryptionException.class,()->EncryptedQueuedWrites.sourceColumns(registry,List.of(wrong))));
        }
    }
}

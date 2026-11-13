package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.JedisPooled;

@EnabledIfSystemProperty(named="cachedb.snapshot.redis",matches="true")
@Timeout(30)
class RedisProjectionMutationFenceRedisTest {
    private static final EntityMetadata<String,String> META=new EntityMetadata<>(){
        public String entityName(){return "parent";}
        public String tableName(){return "parents";}
        public String redisNamespace(){return "parents";}
        public String idColumn(){return "id";}
        public Class<String> entityType(){return String.class;}
        public java.util.function.Function<String,String> idAccessor(){return value->value;}
        public List<String> columns(){return List.of("id");}
        public List<RelationDefinition> relations(){return List.of();}
        public String tenantFieldName(){return "tenant";}
    };
    private static final class Fixture implements AutoCloseable{
        final RedisClientOperations client=JedisPooledOperations.of(new JedisPooled(
                System.getProperty("cachedb.snapshot.redis.host","127.0.0.1"),Integer.getInteger("cachedb.snapshot.redis.port",16383)));
        final RedisKeyStrategy keys=new RedisKeyStrategy("projection-fence-"+UUID.randomUUID());
        final String version=keys.versionKey("parents","id"),tomb=keys.tombstoneKey("parents","id"),owner=version+":tenant",
                payload=keys.entityKey("parents:projection:view","id"),index=keys.indexAllKey("parents:projection:view"),
                meta=keys.indexMetaKey("parents:projection:view","id"),entity=keys.entityKey("parents","id");
        Fixture(){client.commands().set(version,"1");client.commands().set(owner,"a");client.commands().set(entity,"base");client.commands().set(payload,"old");}
        RedisProjectionMutationFence.Parent snapshot(){return RedisProjectionMutationFence.read(client,keys,META,"id");}
        List<List<String>> commands(){return List.of(List.of("SET",payload,"new"),List.of("SADD",index,"id"),List.of("HSET",meta,"v","new"));}
        public void close(){try{client.delete(version,tomb,owner,payload,index,meta,entity);}finally{client.close();}}
    }
    @Test void changedParentVersionCannotPublishPayloadOrAnyIndex(){
        try(var f=new Fixture()){
            var before=f.snapshot();f.client.commands().set(f.version,"2");
            assertFalse(RedisProjectionMutationFence.commit(f.client,f.keys,META,"id",before,f.commands()));
            assertEquals("old",f.client.commands().get(f.payload));assertFalse(f.client.commands().exists(f.index,f.meta)>0);
        }
    }
    @Test void changedTombstoneOrOwnerCannotPublishAndMatchingOwnerCan(){
        try(var f=new Fixture()){
            var before=f.snapshot();f.client.commands().set(f.tomb,"1");
            assertFalse(RedisProjectionMutationFence.commit(f.client,f.keys,META,"id",before,f.commands()));
            f.client.commands().del(f.tomb);f.client.commands().set(f.owner,"b");
            assertFalse(RedisProjectionMutationFence.commit(f.client,f.keys,META,"id",before,f.commands()));
            assertEquals("old",f.client.commands().get(f.payload));
            f.client.commands().set(f.owner,"a");
            assertTrue(RedisProjectionMutationFence.commit(f.client,f.keys,META,"id",before,f.commands()));
            assertEquals("new",f.client.commands().get(f.payload));assertTrue(f.client.commands().sismember(f.index,"id"));
            assertEquals("new",f.client.commands().hget(f.meta,"v"));
        }
    }
    @Test void malformedIndexTypeFailsBeforeTheFirstPayloadMutation(){
        try(var f=new Fixture()){
            var before=f.snapshot();f.client.commands().set(f.index,"wrong-type");
            assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                    ()->RedisProjectionMutationFence.commit(f.client,f.keys,META,"id",before,f.commands()));
            assertEquals("old",f.client.commands().get(f.payload));assertFalse(f.client.commands().exists(f.meta));
        }
    }
}

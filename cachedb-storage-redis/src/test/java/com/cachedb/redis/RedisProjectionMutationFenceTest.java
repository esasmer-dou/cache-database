package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

class RedisProjectionMutationFenceTest {
    private static final EntityMetadata<String,String> META=new EntityMetadata<>(){
        public String entityName(){return "row";}
        public String tableName(){return "rows";}
        public String redisNamespace(){return "rows";}
        public String idColumn(){return "id";}
        public Class<String> entityType(){return String.class;}
        public java.util.function.Function<String,String> idAccessor(){return value->value;}
        public List<String> columns(){return List.of("id");}
        public List<RelationDefinition> relations(){return List.of();}
    };
    @Test void commandBufferUsesTheCanonicalJedisEncoderAndRejectsNetworkOnlyCommands(){
        try(var buffer=new RedisProjectionCommandBuffer()){
            buffer.setex("p",60,"value");buffer.zadd("sort",1,"id");buffer.hset("meta",Map.of("value","x"));
            assertEquals(List.of("SETEX","ZADD","HSET"),buffer.commands().stream().map(row->row.get(0)).toList());
            assertThrows(IllegalArgumentException.class,()->buffer.get("p"));
        }
    }
    @Test void frameLimitsRejectBeforeRedisAndDoNotInventAReceiptOrAWorker(){
        var calls=new AtomicInteger();
        RedisClientOperations client=new RedisClientOperations(){
            public UnifiedJedis commands(){throw new AssertionError("No unbounded command transport");}
            public Object eval(String script,List<String> keys,List<String> args){calls.incrementAndGet();return 1L;}
        };
        var parent=new RedisProjectionMutationFence.Parent("body",1,null,null);
        assertThrows(IllegalArgumentException.class,()->RedisProjectionMutationFence.commit(client,new RedisKeyStrategy("test"),
                META,"id",parent,Collections.nCopies(8193,List.of("DEL","p"))));
        assertThrows(IllegalArgumentException.class,()->RedisProjectionMutationFence.commit(client,new RedisKeyStrategy("test"),
                META,"id",parent,List.of(List.of("SET","p","x".repeat(16*1024*1024)))));
        assertEquals(0,calls.get());
    }
    @Test void staleAndMalformedCommitResultsAreNotReportedAsSuccessfulPublication(){
        var response=new java.util.concurrent.atomic.AtomicReference<Object>(0L);
        RedisClientOperations client=new RedisClientOperations(){
            public UnifiedJedis commands(){throw new AssertionError();}
            public Object eval(String script,List<String> keys,List<String> args){return response.get();}
        };
        var parent=new RedisProjectionMutationFence.Parent("body",1,null,null);
        assertFalse(RedisProjectionMutationFence.commit(client,new RedisKeyStrategy("test"),META,"id",parent,List.of()));
        response.set("1");
        assertThrows(IllegalStateException.class,()->RedisProjectionMutationFence.commit(client,new RedisKeyStrategy("test"),META,"id",parent,List.of()));
    }
    @Test void malformedOrInconsistentParentSnapshotsFailBeforeProjectionPublication(){
        RedisClientOperations client=new RedisClientOperations(){
            public UnifiedJedis commands(){throw new AssertionError();}
            public Object eval(String script,List<String> keys,List<String> args){return Arrays.asList("value","2","1",null);}
        };
        assertThrows(IllegalStateException.class,()->RedisProjectionMutationFence.read(client,new RedisKeyStrategy("test"),META,"id"));
    }
}

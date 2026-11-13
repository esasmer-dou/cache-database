package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.codec.Utf8Length;
import com.reactor.cachedb.core.model.EntityMetadata;
import java.util.*;

/** Background projection reconciliation. Snapshot and commit use the normal Cluster executor. */
final class RedisProjectionMutationFence {
    private static final long BYTES=16L*1024*1024;
    record Parent(String payload,long version,String tombstone,String owner){}
    private static final String READ="""
            if #KEYS~=4 then return redis.error_reply('Invalid projection parent keys') end
            local bytes=0
            for i=1,4 do
              local size=redis.call('STRLEN',KEYS[i])
              if (i==2 or i==3) and size>19 then return redis.error_reply('Invalid projection parent version') end
              if i==4 and size>1024 then return redis.error_reply('Invalid projection parent owner') end
              bytes=bytes+size
              if bytes>16777216 then return redis.error_reply('Projection parent bytes exceed 16 MiB') end
            end
            return {redis.call('GET',KEYS[1]) or false,redis.call('GET',KEYS[2]) or '0',
              redis.call('GET',KEYS[3]) or false,redis.call('GET',KEYS[4]) or false}
            """;
    private static final String COMMIT="""
            if #KEYS<3 or #ARGV<5 then return redis.error_reply('Invalid projection mutation') end
            local bytes=0
            for _,v in ipairs(KEYS) do bytes=bytes+#v end
            for _,v in ipairs(ARGV) do bytes=bytes+#v end
            if bytes>16777216 then return redis.error_reply('Projection mutation bytes exceeded') end
            local count=tonumber(ARGV[5])
            if not count or count<0 or count>8192 or #KEYS~=3+count then return redis.error_reply('Invalid projection command count') end
            if redis.call('STRLEN',KEYS[1])>19 or redis.call('STRLEN',KEYS[2])>19
                or redis.call('STRLEN',KEYS[3])>1024 then return redis.error_reply('Invalid projection fence size') end
            if (redis.call('GET',KEYS[1]) or '0')~=ARGV[1]
                or (redis.call('GET',KEYS[2]) or '')~=ARGV[2]
                or (ARGV[3]=='1' and (redis.call('GET',KEYS[3]) or '')~=ARGV[4]) then return 0 end
            local types={SET='string',SETEX='string',DEL='any',SADD='set',SREM='set',
              ZADD='zset',ZREM='zset',HSET='hash'}
            local frames={};local p=6
            for i=1,count do
              local op,arity=ARGV[p],tonumber(ARGV[p+1]);p=p+2
              if not types[op] or not arity or arity<2 or arity>2050 then return redis.error_reply('Invalid projection command') end
              if (op=='DEL' and arity~=2) or (op=='SET' and arity~=3)
                  or (op=='SETEX' and arity~=4) or ((op=='SADD' or op=='SREM' or op=='ZREM') and arity~=3)
                  or (op=='ZADD' and arity~=4) or (op=='HSET' and (arity<4 or arity%2~=0)) then
                return redis.error_reply('Invalid projection command arity') end
              local c={op,KEYS[3+i]}
              for j=3,arity do
                if not ARGV[p] then return redis.error_reply('Missing projection operand') end
                c[j]=ARGV[p];p=p+1
              end
              if op=='SETEX' and (not tonumber(c[3]) or tonumber(c[3])<1 or tonumber(c[3])>2147483647) then
                return redis.error_reply('Invalid projection TTL') end
              if op=='ZADD' then
                local score=tonumber(c[3])
                if not score or score~=score or score==math.huge or score==-math.huge then
                  return redis.error_reply('Invalid projection score') end
              end
              if not redis.acl_check_cmd(unpack(c)) then return redis.error_reply('Projection command permission denied') end
              local t=redis.call('TYPE',c[2]).ok
              if t~='none' and types[op]~='any' and t~=types[op] then
                return redis.error_reply('Projection command key type mismatch') end
              frames[i]=c
            end
            if p~=#ARGV+1 then return redis.error_reply('Trailing projection mutation operands') end
            for _,c in ipairs(frames) do redis.call(unpack(c)) end
            return 1
            """;
    private RedisProjectionMutationFence(){}
    static Parent read(RedisClientOperations client,RedisKeyStrategy keys,EntityMetadata<?,?> metadata,String id){
        RedisCompositeIndexes.validateMember(id);
        String ns=metadata.redisNamespace();
        var names=List.of(keys.entityKey(ns,id),keys.versionKey(ns,id),keys.tombstoneKey(ns,id),
                keys.versionKey(ns,id)+":tenant");
        client.requireSameSlot(names);
        Object raw=client.eval(READ,names,List.of());
        if(!(raw instanceof List<?> row)||row.size()!=4)throw new IllegalStateException("Malformed projection parent snapshot");
        String payload=nullable(row.get(0)),version=nullable(row.get(1)),tombstone=nullable(row.get(2)),owner=nullable(row.get(3));
        long number=decimal(version);
        if(tombstone!=null&&decimal(tombstone)!=number)throw new IllegalStateException("Inconsistent projection parent tombstone");
        if(metadata.tenantFieldName()!=null&&(owner==null||owner.isBlank()||owner.length()>256))
            throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
        return new Parent(payload,number,tombstone,owner);
    }
    private static String nullable(Object value){
        if(value==null||Boolean.FALSE.equals(value))return null;
        if(value instanceof String s)return s;
        if(value instanceof byte[] b)return new String(b,java.nio.charset.StandardCharsets.UTF_8);
        throw new IllegalStateException("Invalid projection parent value type");
    }
    private static long decimal(String value){
        if(value==null||value.isEmpty()||value.length()>19||value.length()>1&&value.charAt(0)=='0')
            throw new IllegalStateException("Invalid projection parent version");
        for(int i=0;i<value.length();i++)if(value.charAt(i)<'0'||value.charAt(i)>'9')
            throw new IllegalStateException("Invalid projection parent version");
        try{return Long.parseLong(value);}catch(NumberFormatException invalid){throw new IllegalStateException("Invalid projection parent version");}
    }
    static boolean commit(RedisClientOperations client,RedisKeyStrategy keys,EntityMetadata<?,?> parentMetadata,
            String id,Parent expected,List<List<String>> commands){
        if(commands.size()>RedisProjectionCommandBuffer.MAX_COMMANDS)throw new IllegalArgumentException("Too many projection commands");
        String ns=parentMetadata.redisNamespace();
        var names=new ArrayList<String>();names.add(keys.versionKey(ns,id));names.add(keys.tombstoneKey(ns,id));
        names.add(keys.versionKey(ns,id)+":tenant");
        var args=new ArrayList<String>();args.add(Long.toString(expected.version()));args.add(expected.tombstone()==null?"":expected.tombstone());
        args.add(parentMetadata.tenantFieldName()==null?"0":"1");args.add(expected.owner()==null?"":expected.owner());
        args.add(Integer.toString(commands.size()));
        long bytes=0;
        for(var command:commands){
            if(command.size()<2||command.size()>RedisProjectionCommandBuffer.MAX_ARITY)
                throw new IllegalArgumentException("Invalid projection command arity");
            names.add(command.get(1));args.add(command.get(0));args.add(Integer.toString(command.size()));
            args.addAll(command.subList(2,command.size()));
        }
        for(String value:names){bytes+=Utf8Length.of(value);if(bytes>BYTES)throw new IllegalArgumentException("Projection framing exceeds 16 MiB");}
        for(String value:args){bytes+=Utf8Length.of(value);if(bytes>BYTES)throw new IllegalArgumentException("Projection framing exceeds 16 MiB");}
        client.requireSameSlot(names);
        Object result=client.eval(COMMIT,names,args);
        if(Long.valueOf(0).equals(result))return false;
        if(!Long.valueOf(1).equals(result))throw new IllegalStateException("Malformed projection commit reply");
        return true;
    }
}

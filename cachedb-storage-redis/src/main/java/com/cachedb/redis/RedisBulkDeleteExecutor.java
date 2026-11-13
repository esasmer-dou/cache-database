package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.codec.Utf8Length;
import com.reactor.cachedb.core.model.EntityMetadata;
import java.util.*;
import redis.clients.jedis.util.JedisClusterCRC16;

/** Bounded, caller-owned mutation preparation. No SQL, executor, retry loop or ambient context transfer. */
final class RedisBulkDeleteExecutor {
    static final int MAX_IDS = 10_000, CHUNK = 128;
    static final long BYTES = 16L * 1024 * 1024;
    record State(long version, String payload, String owner, String hotOwner, Map<String, String> meta) {}
    private static final String READ = """
            local n=tonumber(ARGV[1]); local budget=tonumber(ARGV[2]); local watched=ARGV[3]=='1'
            if not n or n<1 or n>128 or #KEYS~=6*n or not budget or budget<1 or budget>16777216 then
              return redis.error_reply('Invalid bounded delete snapshot') end
            local bytes=0
            for i=1,n do
              local b=(i-1)*6
              for j=1,6 do
                if j~=5 and (j~=1 or watched) then
                  bytes=bytes+redis.call('STRLEN',KEYS[b+j])
                  if bytes>budget then return redis.error_reply('Delete snapshot byte budget exceeded') end
                end
              end
              if redis.call('HLEN',KEYS[b+5])>1024 then return redis.error_reply('Too many delete index fields') end
              local fields=redis.call('HKEYS',KEYS[b+5])
              for _,field in ipairs(fields) do
                bytes=bytes+#field+redis.call('HSTRLEN',KEYS[b+5],field)
                if bytes>budget then return redis.error_reply('Delete snapshot byte budget exceeded') end
              end
            end
            local rows={}
            for i=1,n do
              local b=(i-1)*6
              rows[i]={redis.call('GET',KEYS[b+2]) or '0',
                watched and not redis.call('GET',KEYS[b+6]) and (redis.call('GET',KEYS[b+1]) or false) or false,
                redis.call('GET',KEYS[b+3]) or false,redis.call('GET',KEYS[b+4]) or false,
                redis.call('HGETALL',KEYS[b+5])}
            end
            return {bytes,rows}
            """;
    private static final String MAINTAIN = """
            local p,k=1,1
            local n=tonumber(ARGV[p]);p=p+1
            if not n or n<1 or n>128 then return redis.error_reply('Invalid projection rows') end
            local allowed={DEL=true,SREM=true,ZREM=true,SADD=true,SET=true}
            local rows={};local total=0
            for i=1,n do
              local version,count=ARGV[p],tonumber(ARGV[p+1]);p=p+2
              if not version or not string.match(version,'^[1-9][0-9]*$') or not count or count<0 then
                return redis.error_reply('Invalid projection fence') end
              total=total+count
              if total>8192 then return redis.error_reply('Projection maintenance count exceeded') end
              local row={version=version,versionKey=KEYS[k],tombstoneKey=KEYS[k+1],commands={}};k=k+2
              if not row.versionKey or not row.tombstoneKey then return redis.error_reply('Missing projection fence keys') end
              for j=1,count do
                local op,arity=ARGV[p],tonumber(ARGV[p+1]);p=p+2
                if not allowed[op] or not arity or arity<2 or arity>4 or not KEYS[k] then
                  return redis.error_reply('Invalid projection command') end
                local c={op,KEYS[k]};k=k+1
                for a=3,arity do
                  if not ARGV[p] then return redis.error_reply('Missing projection operand') end
                  c[a]=ARGV[p];p=p+1
                end
                row.commands[j]=c
              end
              rows[i]=row
            end
            if p~=#ARGV+1 or k~=#KEYS+1 then return redis.error_reply('Invalid projection framing') end
            for _,row in ipairs(rows) do
              if redis.call('STRLEN',row.versionKey)>19 or redis.call('STRLEN',row.tombstoneKey)>19 then
                return redis.error_reply('Invalid parent version size') end
              local current=redis.call('GET',row.versionKey) or '0'
              if current==row.version then
                if redis.call('GET',row.tombstoneKey)~=row.version then return redis.error_reply('Projection delete fence unavailable') end
                for _,c in ipairs(row.commands) do redis.call(unpack(c)) end
              elseif not string.match(current,'^[1-9][0-9]*$')
                  or (#current<#row.version or (#current==#row.version and current<row.version)) then
                return redis.error_reply('Projection parent version unavailable')
              end
              -- A newer parent version supersedes this cleanup. Do not erase the newer projection.
            end
            return n
            """;

    private RedisBulkDeleteExecutor() {}
    static <ID> List<ID> copyIds(Collection<ID> input) {
        Objects.requireNonNull(input, "ids");
        if (input.size()>MAX_IDS) throw new IllegalArgumentException("Bulk delete exceeds 10000 input IDs");
        var unique=new LinkedHashMap<String,ID>(); int count=0; long bytes=0;
        for (ID id:input) {
            if (++count>MAX_IDS) throw new IllegalArgumentException("Bulk delete exceeds 10000 input IDs");
            String text=idText(Objects.requireNonNull(id,"id"));
            bytes+=Utf8Length.of(text);
            if (bytes>BYTES) throw new IllegalArgumentException("Bulk delete ID bytes exceed 16 MiB");
            ID prior=unique.putIfAbsent(text,id);
            if (prior!=null && !prior.equals(id)) throw new IllegalArgumentException("Ambiguous bulk identity types");
        }
        return List.copyOf(unique.values());
    }
    private static String idText(Object id) {
        if (!(id instanceof String || id instanceof Long || id instanceof Integer || id instanceof Short
                || id instanceof Byte || id instanceof UUID))
            throw new IllegalArgumentException("Bulk delete requires a String, integral or UUID ID");
        String value=String.valueOf(id);
        RedisCompositeIndexes.validateMember(value);
        return value;
    }
    static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Bulk delete caller interrupted");
        if (System.nanoTime()-deadline>=0) throw new IllegalStateException("Bulk delete deadline elapsed");
    }
    static void validateWire(List<String> keys,List<String> args) {
        if (keys.size()>16384 || args.size()>65536) throw new IllegalArgumentException("Bulk delete wire count exceeded");
        long bytes=0;
        for (String key:keys) {
            if (key==null || key.length()>16384) throw new IllegalArgumentException("Invalid delete key");
            bytes+=Utf8Length.of(key);
            if (bytes>BYTES) throw new IllegalArgumentException("Bulk delete wire exceeds 16 MiB");
        }
        for (String arg:args) {
            bytes+=Utf8Length.of(Objects.requireNonNull(arg));
            if (bytes>BYTES) throw new IllegalArgumentException("Bulk delete wire exceeds 16 MiB");
        }
    }
    static <ID> void requireOwners(RedisClientOperations client,RedisKeyStrategy keys,
            EntityMetadata<?,ID> metadata,List<ID> ids,String tenant,long deadline) {
        if (metadata.tenantFieldName()==null) return;
        if (tenant==null) throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
        for (int start=0;start<ids.size();start+=CHUNK) {
            checkDeadline(deadline);
            var batch=ids.subList(start,Math.min(ids.size(),start+CHUNK));
            var names=batch.stream().map(id->keys.versionKey(metadata.redisNamespace(),id)+":tenant").toList();
            var owners=RedisAsyncBoundedValues.read(client,names,CHUNK*1024L);
            for (String owner:owners) if (!tenant.equals(owner))
                throw new com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException(metadata.entityName());
        }
    }
    static <ID> List<State> states(RedisClientOperations client,RedisKeyStrategy keys,
            EntityMetadata<?,ID> metadata,List<ID> ids,boolean watched,long deadline) {
        if (ids.isEmpty() || ids.size()>CHUNK) throw new IllegalArgumentException("Snapshot chunk must contain 1..128 IDs");
        var groups=new LinkedHashMap<Integer,List<Integer>>();
        for (int i=0;i<ids.size();i++) {
            int slot=client.clustered()?JedisClusterCRC16.getSlot(keys.entityKey(metadata.redisNamespace(),ids.get(i))):0;
            groups.computeIfAbsent(slot,ignored->new ArrayList<>()).add(i);
        }
        var result=new ArrayList<State>(Collections.nCopies(ids.size(),null)); long remaining=BYTES;
        for (var group:groups.values()) {
            checkDeadline(deadline);
            var names=new ArrayList<String>();
            for (int index:group) {
                ID id=ids.get(index); String ns=metadata.redisNamespace();
                names.add(keys.entityKey(ns,id)); names.add(keys.versionKey(ns,id));
                names.add(keys.versionKey(ns,id)+":tenant"); names.add(keys.tenantHotOwnerKey(ns,id));
                names.add(keys.indexMetaKey(ns,id)); names.add(keys.tombstoneKey(ns,id));
            }
            client.requireSameSlot(names);
            Object raw=client.eval(READ,names,List.of(Integer.toString(group.size()),Long.toString(remaining),watched?"1":"0"));
            if (!(raw instanceof List<?> envelope) || envelope.size()!=2 || !(envelope.get(1) instanceof List<?> rows)
                    || rows.size()!=group.size()) throw new IllegalStateException("Malformed delete snapshot");
            long bytes=Long.parseLong(text(envelope.get(0)));
            if (bytes<0 || bytes>remaining) throw new IllegalStateException("Invalid delete snapshot budget");
            remaining-=bytes;
            for (int i=0;i<rows.size();i++) {
                if (!(rows.get(i) instanceof List<?> row) || row.size()!=5 || !(row.get(4) instanceof List<?> hash)
                        || hash.size()%2!=0 || hash.size()>2048) throw new IllegalStateException("Malformed delete row");
                var meta=new LinkedHashMap<String,String>();
                for (int f=0;f<hash.size();f+=2) {
                    if (meta.put(text(hash.get(f)),text(hash.get(f+1)))!=null)
                        throw new IllegalStateException("Duplicate index metadata field");
                }
                long version=Long.parseLong(text(row.get(0)));
                if (version<0) throw new IllegalStateException("Invalid delete version");
                result.set(group.get(i),new State(version,nullable(row.get(1)),nullable(row.get(2)),
                        nullable(row.get(3)),Map.copyOf(meta)));
            }
        }
        return List.copyOf(result);
    }
    static String text(Object value) {
        return value instanceof byte[] b?new String(b,java.nio.charset.StandardCharsets.UTF_8):String.valueOf(value);
    }
    private static String nullable(Object value) { return value==null || Boolean.FALSE.equals(value)?null:text(value); }

    static List<List<String>> maintenance(RedisKeyStrategy keys,EntityMetadata<?,?> metadata,
            Object id,State state,List<List<String>> indexCommands) {
        var commands=new ArrayList<List<String>>(indexCommands);
        String ns=metadata.redisNamespace(),member=String.valueOf(id);
        commands.add(List.of("ZREM",keys.hotSetKey(ns),member));
        String record=state.hotOwner();
        if (record!=null && !record.isBlank()) {
            var parts=record.split("\\n",-1);
            if (parts.length>3) throw new IllegalArgumentException("Invalid tenant admission record");
            if (!parts[0].isBlank()) commands.add(List.of("ZREM",parts[0],member));
            if (parts.length>2 && !parts[1].isBlank()) {
                long amount=Long.parseLong(parts[2]);
                if (amount<0) throw new IllegalArgumentException("Invalid tenant admission bytes");
                if (amount>0) commands.add(List.of("CLAMP",parts[1],Long.toString(amount)));
            }
        }
        commands.add(List.of("DEL",keys.tenantHotOwnerKey(ns,id)));
        return List.copyOf(commands);
    }

    static <P,ID> void removeProjection(RedisClientOperations client,EntityMetadata<P,ID> metadata,
            RedisKeyStrategy keys,RedisQueryIndexManager<P,ID> indexes,Map<ID,Long> versions,
            String parentNamespace,long deadline) {
        var ids=List.copyOf(versions.keySet());
        if (ids.isEmpty() || ids.size()>CHUNK) throw new IllegalArgumentException("Projection delete chunk requires 1..128 IDs");
        indexes.withCompositeMutation(()->{
            checkDeadline(deadline);
            var snapshots=states(client,keys,metadata,ids,false,deadline);
            var names=new ArrayList<String>();var args=new ArrayList<String>();args.add(Integer.toString(ids.size()));
            long bytes=0;int count=0;
            for (int i=0;i<ids.size();i++) {
                ID id=ids.get(i);
                var commands=new ArrayList<List<String>>(indexes.bulkDeleteCommands(id,snapshots.get(i).meta()));
                commands.add(List.of("DEL",keys.entityKey(metadata.redisNamespace(),id)));
                commands.add(List.of("DEL",keys.tombstoneKey(metadata.redisNamespace(),id)));
                names.add(keys.versionKey(parentNamespace,id));names.add(keys.tombstoneKey(parentNamespace,id));
                args.add(Long.toString(versions.get(id)));args.add(Integer.toString(commands.size()));
                for (var command:commands) {
                    if (++count>RedisDeleteCommandBuffer.MAX_COMMANDS) throw new IllegalArgumentException("Projection command count exceeded");
                    for (String arg:command) bytes+=Utf8Length.of(arg);
                    if (bytes>BYTES) throw new IllegalArgumentException("Projection command byte budget exceeded");
                    names.add(command.get(1));args.add(command.get(0));args.add(Integer.toString(command.size()));
                    args.addAll(command.subList(2,command.size()));
                }
            }
            validateWire(names,args);client.requireSameSlot(names);checkDeadline(deadline);
            client.eval(MAINTAIN,names,args);
            indexes.bulkDeleteObserved();return null;
        });
    }

}

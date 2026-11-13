package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.codec.PayloadEntityCodec;
import com.reactor.cachedb.core.config.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.projection.ProjectionEntityMetadata;
import com.reactor.cachedb.core.retention.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import static com.reactor.cachedb.core.retention.RetentionException.Code.*;

/** Native bounded Redis retention; borrows the client, creates no worker or scheduler. */
public final class RedisRetentionAdapter<T,ID> implements RetentionTarget {
    public record Settings(String stream, String compactionStream, int compactionShards,
            int maxKeys, int maxCommandBytes, int leaseMillis, boolean generationGuarded) {
        public Settings {
            if (stream==null || compactionStream==null || stream.length()>1024 || compactionStream.length()>1024
                    || compactionShards<1 || compactionShards>128 || maxKeys<32 || maxKeys>4096
                    || maxCommandBytes<16384 || maxCommandBytes>8*1024*1024 || leaseMillis<15000 || leaseMillis>120000)
                throw new IllegalArgumentException("Invalid retention settings");
        }
    }
    private record Candidate(String id,String score) {}
    private record Row(Candidate candidate,String payload,String version,String owner,String hot,String generation,
            Map<String,String> values,RedisRetentionIndexPlan.Plan plan) {}
    private final RedisClientOperations redis;
    private final RedisKeyStrategy keys;
    private final EntityMetadata<T,ID> metadata;
    private final EntityCodec<T> codec;
    private final Function<String,ID> decodeId;
    private final RetentionPolicy policy;
    private final QueryIndexConfig indexes;
    private final CachePolicy cachePolicy;
    private final RedisGuardrailConfig guardrails;
    private final WriteBehindConfig writeBehind;
    private final RedisFunctionArgsMapper mapper;
    private final RedisProducerGuard producer;
    private final RedisNearCacheCoordinator.Store local;
    private final Clock clock;
    private final Settings settings;
    private final List<String> fields;
    private final String lease,epoch,sort,select,owners,snapshot,mutation,scope;
    private final Semaphore capacity=new Semaphore(1);

    public RedisRetentionAdapter(RedisClientOperations redis,RedisKeyStrategy keys,EntityMetadata<T,ID> metadata,
            EntityCodec<T> codec,Function<String,ID> decodeId,CachePolicy cachePolicy,QueryIndexConfig indexes,
            RedisFunctionsConfig functions,RedisGuardrailConfig guardrails,WriteBehindConfig writeBehind,
            RedisFunctionArgsMapper configuredMapper,RedisProducerGuard producer,
            RedisNearCacheCoordinator.Store local,Clock clock,Settings settings) {
        this.redis=Objects.requireNonNull(redis); this.keys=Objects.requireNonNull(keys);
        this.metadata=Objects.requireNonNull(metadata);
        this.policy=metadata.retentionPolicy().orElseThrow(()->new IllegalArgumentException("Missing retention descriptor"));
        policy.validate(metadata);
        if (!indexes.rangeIndexEnabled() || metadata instanceof ProjectionEntityMetadata<?,?>)
            throw new IllegalArgumentException("Retention requires an entity sorted-index layout");
        this.codec=PayloadEntityCodec.wrap(Objects.requireNonNull(codec),metadata,metadata.compressionThresholdBytes());
        this.decodeId=Objects.requireNonNull(decodeId); this.indexes=Objects.requireNonNull(indexes);
        this.cachePolicy=Objects.requireNonNull(cachePolicy);
        if (local!=null && !cachePolicy.nearCachePolicy().enabled())
            throw new IllegalArgumentException("Local L1 requires the actual near-cache policy");
        this.guardrails=Objects.requireNonNull(guardrails); this.writeBehind=Objects.requireNonNull(writeBehind);
        this.mapper=Objects.requireNonNull(configuredMapper); this.producer=Objects.requireNonNull(producer);
        this.local=local; this.clock=Objects.requireNonNull(clock); this.settings=Objects.requireNonNull(settings);
        if(metadata.tenantPurgeEnabled() && !settings.generationGuarded())
            throw new IllegalArgumentException("Protected retention requires original generation checks");
        fields=RedisRetentionIndexPlan.fields(metadata,indexes);
        String ns=metadata.redisNamespace();
        if (ns==null || ns.length()>256) throw new IllegalArgumentException("Invalid retention namespace");
        scope=hash(ns); String base=keys.namespacePrefix(ns)+":retention:"+hash(policy.field());
        lease=base+":lease"; epoch=base+":epoch"; sort=keys.indexSortKey(ns,policy.field());
        select=RedisRetentionScripts.read("select"); owners=RedisRetentionScripts.read("owners"); snapshot=RedisRetentionScripts.read("snapshot");
        mutation=policy.action()==RetentionPolicy.Action.DELETE ? RedisRetentionScripts.mutation(functions)
                : RedisRetentionScripts.read("mutate");
        redis.requireSameSlot(List.of(lease,epoch,sort,keys.entityKey(ns,""),keys.versionKey(ns,""),
                keys.indexMetaKey(ns,""),keys.writeStreamKey(settings.stream(),ns),
                keys.compactionStatsKey(ns),keys.compactionStreamKey(settings.compactionStream(),ns,"",settings.compactionShards())));
        var roles=List.of(keys.entityKey(ns,"x"),keys.versionKey(ns,"x"),keys.tombstoneKey(ns,"x"),
                keys.indexMetaKey(ns,"x"),keys.tenantHotOwnerKey(ns,"x"),keys.compactionPayloadKey(ns,"x"));
        if (new HashSet<>(roles).size()!=roles.size()) throw new IllegalArgumentException("Overlapping retention key roles");
    }
    public Class<?> entityType() { return metadata.entityType(); }
    public String scopeToken() { return scope; }
    @Override public RetentionSweepResult sweep(int maxRows) {
        if (!CacheTenantContext.isAdministrative()) throw new RetentionException(ADMIN_REQUIRED);
        if (maxRows<1 || maxRows>10000) throw new RetentionException(INVALID_REQUEST);
        if (!capacity.tryAcquire()) throw new RetentionException(BUSY);
        String token=null;
        try {
            String owner=UUID.randomUUID().toString();
            Object acquired=redis.eval("if redis.call('EXISTS',KEYS[1])==1 then return false end; "
                    +"redis.call('INCR',KEYS[2]); local t=ARGV[1]..':'..redis.call('GET',KEYS[2]); "
                    +"redis.call('SET',KEYS[1],t,'PX',ARGV[2]); return t",
                    List.of(lease,epoch),List.of(owner,Integer.toString(settings.leaseMillis())));
            if (acquired==null || Boolean.FALSE.equals(acquired)) throw new RetentionException(BUSY);
            token=text(acquired); long cutoff=policy.cutoff(clock), deadline=System.nanoTime()+30_000_000_000L;
            int examined=0,evicted=0,cleaned=0,accepted=0,skipped=0,offset=0; boolean exhausted=false;
            for (int step=0; step<256 && examined<maxRows && System.nanoTime()<deadline; step++) {
                renew(token);
                List<Candidate> candidates=select(token,cutoff,offset,Math.min(128,maxRows-examined));
                if (candidates.isEmpty()) { exhausted=true; break; }
                List<Row> rows=snapshot(token,candidates,cutoff);
                int proved=rows.size();
                // Unprovable payload/ownership remains indexed and is skipped, never sourced from SQL.
                int rejected=candidates.size()-proved;
                examined+=rejected; skipped+=rejected; offset+=rejected;
                if (rows.isEmpty()) continue;
                var command=new Command(token,cutoff);
                var admitted=new ArrayList<Row>();
                for (Row row:rows) {
                    if (!command.add(row)) break;
                    admitted.add(row);
                }
                if (admitted.isEmpty()) throw new RetentionException(INPUT_LIMIT);
                if (policy.action()==RetentionPolicy.Action.DELETE) producer.applyBackpressure();
                List<?> result;
                invalidate(admitted);
                try {
                    result=list(redis.eval(mutation,command.keys(),command.args()));
                } finally {
                    // Advance segment stamps and legacy global epochs even on an unknown I/O outcome.
                    invalidate(admitted);
                }
                if (result.size()!=admitted.size()) throw new RetentionException(STORAGE_FAILURE);
                for (int i=0;i<result.size();i++) {
                    List<?> pair=list(result.get(i)); if (pair.size()!=2) throw new RetentionException(STORAGE_FAILURE);
                    examined++;
                    if (number(pair.get(0))==0) { skipped++; offset++; continue; }
                    cleaned++; if (policy.action()==RetentionPolicy.Action.DELETE) accepted++; else evicted+=number(pair.get(1));
                }
                if (admitted.size()<rows.size()) break;
            }
            return new RetentionSweepResult(examined,evicted,cleaned,accepted,skipped,exhausted,cutoff,clock.instant());
        } catch (RuntimeException failure) {
            if (failure instanceof RetentionException safe) throw safe;
            String message=failure.getMessage();
            throw new RetentionException(message!=null && message.contains("RETENTION_LEASE_LOST") ? LEASE_LOST
                    : message!=null && message.contains("RETENTION_INPUT_LIMIT") ? INPUT_LIMIT
                    : message!=null && message.contains("RETENTION_LAYOUT") ? UNSUPPORTED_LAYOUT : STORAGE_FAILURE);
        } finally {
            try {
                if (token!=null) redis.eval("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end; return 0",
                        List.of(lease),List.of(token));
            } catch (RuntimeException releaseFailure) { throw new RetentionException(STORAGE_FAILURE);
            } finally { capacity.release(); }
        }
    }
    private void renew(String token) {
        Object value=redis.eval("if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end; return redis.call('PEXPIRE',KEYS[1],ARGV[2])",
                List.of(lease),List.of(token,Integer.toString(settings.leaseMillis())));
        if (number(value)!=1) throw new RetentionException(LEASE_LOST);
    }
    private List<Candidate> select(String token,long cutoff,int offset,int limit) {
        List<?> raw=list(redis.eval(select,List.of(lease,sort),List.of(token,Integer.toString(limit),Long.toString(cutoff),Integer.toString(offset))));
        if (raw.size()>256 || raw.size()%2!=0) throw new RetentionException(INPUT_LIMIT);
        var result=new ArrayList<Candidate>(raw.size()/2);
        for (int i=0;i<raw.size();i+=2) result.add(new Candidate(text(raw.get(i)),text(raw.get(i+1))));
        return result;
    }
    private void invalidate(List<Row> rows) {
        if (local!=null) for (Row row:rows) local.invalidate(row.candidate().id());
    }
    private String generationKey(String owner) {
        return keys.namespacePrefix(metadata.redisNamespace())+":generation:"+hash(metadata.redisNamespace()+"\0"+owner);
    }
    private List<Row> snapshot(String token,List<Candidate> candidates,long cutoff) {
        String ns=metadata.redisNamespace();
        var ownerKeys=new ArrayList<String>(); ownerKeys.add(lease);
        for (Candidate c:candidates) ownerKeys.add(keys.versionKey(ns,c.id())+":tenant");
        List<?> observedOwners=list(redis.eval(owners,ownerKeys,List.of(token)));
        if (observedOwners.size()!=candidates.size()) throw new RetentionException(STORAGE_FAILURE);
        var readKeys=new ArrayList<String>(); readKeys.add(lease);
        for (int i=0;i<candidates.size();i++) {
            String id=candidates.get(i).id(),owner=text(observedOwners.get(i));
            readKeys.addAll(List.of(keys.entityKey(ns,id),keys.versionKey(ns,id),keys.versionKey(ns,id)+":tenant",
                    keys.tenantHotOwnerKey(ns,id),keys.indexMetaKey(ns,id),
                    settings.generationGuarded()?generationKey(owner):lease));
        }
        var args=new ArrayList<String>(List.of(token,Integer.toString(candidates.size()),Integer.toString(fields.size()),
                settings.generationGuarded()?"1":"0"));
        args.addAll(fields); observedOwners.forEach(owner->args.add(text(owner)));
        List<?> raw=list(redis.eval(snapshot,readKeys,args));
        if (raw.size()!=candidates.size()) throw new RetentionException(STORAGE_FAILURE);
        var result=new ArrayList<Row>();
        for (int i=0;i<raw.size();i++) {
            if (raw.get(i)==null || Boolean.FALSE.equals(raw.get(i))) continue;
            List<?> row=list(raw.get(i)); if (row.size()!=6) throw new RetentionException(STORAGE_FAILURE);
            String payload=text(row.get(0)),version=text(row.get(1)),owner=text(row.get(2)),hot=text(row.get(3)),generation=text(row.get(5));
            if (metadata.tenantFieldName()!=null && (owner.isBlank() || owner.length()>256)) continue;
            if (version.isEmpty()) continue;
            Candidate c=candidates.get(i);
            if (!payload.isEmpty()) {
                T entity=codec.fromRedisValue(payload);
                if (!String.valueOf(metadata.idAccessor().apply(entity)).equals(c.id())) throw new RetentionException(UNSUPPORTED_LAYOUT);
                if (metadata.tenantFieldName()!=null && !owner.equals(metadata.extractTenantId(entity))) throw new RetentionException(UNSUPPORTED_LAYOUT);
                Double actual=RedisScoreSupport.toScore(metadata.columnTypes(),policy.field(),codec.toColumns(entity).get(policy.field()));
                if (actual==null || !Double.isFinite(actual) || actual>cutoff) continue;
            } else if (policy.action()==RetentionPolicy.Action.DELETE) continue;
            List<?> encoded=list(row.get(4)); if (encoded.size()!=fields.size()) throw new RetentionException(STORAGE_FAILURE);
            var values=new LinkedHashMap<String,String>();
            for (int j=0;j<fields.size();j++) if (encoded.get(j)!=null && !Boolean.FALSE.equals(encoded.get(j))) values.put(fields.get(j),text(encoded.get(j)));
            var plan=RedisRetentionIndexPlan.plan(keys,metadata,indexes,values,hot);
            result.add(new Row(c,payload,version,owner,hot,generation,Map.copyOf(values),plan));
        }
        return result;
    }
    private List<String> deleteArgs(Row row) {
        ID id=decodeId.apply(row.candidate().id());
        if (!String.valueOf(id).equals(row.candidate().id())) throw new RetentionException(UNSUPPORTED_LAYOUT);
        var operation=new WriteOperation<T,ID>(OperationType.DELETE,metadata,id,Map.of(metadata.idColumn(),id),"",0,clock.instant(),null,Map.of());
        var args=new ArrayList<String>();
        try (var context=(row.owner().isEmpty()?CacheTenantContext.administrative():CacheTenantContext.withTenant(row.owner()))) {
            args.addAll(mapper.deleteArgs(operation,guardrails,writeBehind,cachePolicy));
        }
        if (args.size()>20) throw new RetentionException(UNSUPPORTED_LAYOUT);
        while (args.size()<20) args.add("");
        args.add(row.version());
        if (settings.generationGuarded()) args.addAll(List.of("cachedb:generation:v1","9",row.generation(),
                hash(metadata.redisNamespace()+"\0"+row.owner()),scope,"cachedb:version-range:v1","10","11"));
        return List.copyOf(args);
    }
    private final class Command {
        private final LinkedHashMap<String,Integer> positions=new LinkedHashMap<>();
        private final ArrayList<String> arguments=new ArrayList<>();
        private int bytes,rows;
        Command(String token,long cutoff) {
            bytes=mutation.getBytes(StandardCharsets.UTF_8).length+256;
            key(lease); key(sort); arguments.add(token); arguments.add(policy.action().name());
            arguments.add(Long.toString(cutoff)); arguments.add("0");
        }
        int key(String value) {
            if (value.isEmpty()) return 0;
            if (value.length()>8192) throw new RetentionException(INPUT_LIMIT);
            Integer known=positions.get(value);
            if (known!=null) return known;
            bytes=Math.addExact(bytes,value.getBytes(StandardCharsets.UTF_8).length+16);
            int next=positions.size()+1; positions.put(value,next); return next;
        }
        void add(String value) { bytes=Math.addExact(bytes,value.getBytes(StandardCharsets.UTF_8).length+16); arguments.add(value); }
        boolean add(Row row) {
            int oldSize=arguments.size(),oldKeys=positions.size(),oldBytes=bytes;
            String ns=metadata.redisNamespace(),id=row.candidate().id();
            add(id); add(row.candidate().score()); add(row.version()); add(row.owner()); add(row.hot()); add(row.payload());
            String tenantToken=hash(ns+"\0"+row.owner());
            add(Integer.toString(settings.generationGuarded()?key(generationKey(row.owner())):0));
            boolean allocated=settings.generationGuarded() && policy.action()==RetentionPolicy.Action.DELETE;
            String allocator=keys.namespacePrefix(ns)+":security:version:"+tenantToken;
            add(Integer.toString(allocated?key(allocator):0));add(Integer.toString(allocated?key(allocator+":next"):0));
            add(row.generation()); add(tenantToken); add(scope);
            List<String> normalArgs=policy.action()==RetentionPolicy.Action.DELETE ? deleteArgs(row) : List.of();
            var invalidation=normalArgs.isEmpty() ? mapper.nearInvalidation(ns,id,cachePolicy)
                    : new RedisFunctionArgsMapper.NearInvalidation(normalArgs.get(16),normalArgs.get(17));
            add(invalidation.channel()); add(invalidation.message());
            for (String k:List.of(keys.entityKey(ns,id),keys.versionKey(ns,id),keys.tombstoneKey(ns,id),
                    keys.writeStreamKey(settings.stream(),ns),keys.compactionPayloadKey(ns,id),keys.compactionPendingKey(ns,id),
                    keys.compactionStreamKey(settings.compactionStream(),ns,id,settings.compactionShards()),
                    keys.compactionStatsKey(ns),keys.versionKey(ns,id)+":tenant")) add(Integer.toString(key(k)));
            add(Integer.toString(key(keys.indexMetaKey(ns,id)))); add(Integer.toString(key(keys.tenantHotOwnerKey(ns,id))));
            add(Integer.toString(key(row.plan().tenantLock()))); add(Integer.toString(key(row.plan().tenantBytes()))); add(Long.toString(row.plan().bytes()));
            add(Integer.toString(row.values().size()));
            row.values().forEach((field,value)->{ add(field); add(value); });
            add(Integer.toString(row.plan().sets().size())); for (String k:row.plan().sets()) add(Integer.toString(key(k)));
            var sorted=new LinkedHashSet<>(row.plan().sorted()); sorted.add(sort);
            add(Integer.toString(sorted.size())); for (String k:sorted) add(Integer.toString(key(k)));
            add(Integer.toString(normalArgs.size())); normalArgs.forEach(this::add);
            if (positions.size()>settings.maxKeys() || bytes>settings.maxCommandBytes()) {
                while (arguments.size()>oldSize) arguments.remove(arguments.size()-1);
                var iterator=positions.entrySet().iterator(); int i=0;
                while (iterator.hasNext()) { iterator.next(); if (i++>=oldKeys) iterator.remove(); }
                bytes=oldBytes; return false;
            }
            rows++; arguments.set(3,Integer.toString(rows)); return true;
        }
        List<String> keys() { return List.copyOf(positions.keySet()); }
        List<String> args() { return List.copyOf(arguments); }
    }
    static String hash(String value) {
        if (value==null || value.length()>4096) throw new RetentionException(INPUT_LIMIT);
        try {
            var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            var digest=MessageDigest.getInstance("SHA-256"); digest.update(encoded);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.nio.charset.CharacterCodingException invalid) { throw new RetentionException(INPUT_LIMIT); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static List<?> list(Object value) { if (value instanceof List<?> list) return list; throw new RetentionException(STORAGE_FAILURE); }
    private static String text(Object value) { return value==null || Boolean.FALSE.equals(value) ? "" : (String)value; }
    private static int number(Object value) { return Math.toIntExact(((Number)value).longValue()); }
}

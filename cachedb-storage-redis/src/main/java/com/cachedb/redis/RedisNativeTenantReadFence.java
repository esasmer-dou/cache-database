package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Native acknowledgement journal for the facade's exact TenantReadFence SPI.
 * A control transport must invoke acknowledge on EVERY approved runtime. Missing delivery
 * remains DRAINING; unknown membership/producer/store coverage remains INCOMPLETE.
 */
public final class RedisNativeTenantReadFence implements TenantReadFence {
    public record RuntimeManifest(String token, List<UUID> runtimes, boolean complete) {
        public RuntimeManifest {
            ProtectionKey.token(token);
            if (runtimes == null || runtimes.size() > 128) throw invalid();
            var ordered = new ArrayList<>(runtimes);
            ordered.sort(Comparator.comparing(UUID::toString));
            if (new HashSet<>(ordered).size() != ordered.size()) throw invalid();
            runtimes = List.copyOf(ordered);
        }
    }
    /**
     * Mandatory native producer admission/drain AND complete declared store/route attestation.
     * CLOSED must cover source fills, projections, direct producers and recovery callbacks;
     * all participating primary run IDs/topology and the approved store manifest;
     * a missing/unknown route is INCOMPLETE. Never substitute a default true probe.
     */
    @FunctionalInterface public interface NativeClosureProbe {
        Probe inspect(Lease lease, long generation, String manifestToken);
    }
    public record Probe(State state, String token) {
        public Probe {
            Objects.requireNonNull(state);
            if (state == State.CLOSED) ProtectionKey.token(token);
        }
    }

    private final RedisClientOperations redis;
    private final RedisGenerationBarrier barrier;
    private final ProtectionKey key;
    private final RuntimeManifest manifest;
    private final RedisTenantReadFence local;
    private final NativeClosureProbe probe;
    private final String journal;
    private final String roster;
    private final int freshnessMillis;

    public RedisNativeTenantReadFence(RedisClientOperations redis, RedisGenerationBarrier barrier,
            ProtectionKey key, RuntimeManifest manifest, RedisTenantReadFence local,
            NativeClosureProbe probe, int freshnessMillis) {
        this.redis = Objects.requireNonNull(redis);
        this.barrier = Objects.requireNonNull(barrier);
        this.key = Objects.requireNonNull(key);
        this.manifest = Objects.requireNonNull(manifest);
        this.local = Objects.requireNonNull(local);
        this.probe = probe;
        if (freshnessMillis < 100 || freshnessMillis > 10000) throw invalid();
        this.freshnessMillis = freshnessMillis;
        journal = barrier.generationKey(key) + ":read-closure";
        roster = digest(manifest.runtimes().stream().map(UUID::toString).toList());
    }

    @Override public Evidence closeAndDrain(Lease lease, long generation, String manifestToken,
            String checkpoint, int maxOperations, int maxMillis) {
        validate(lease, generation, manifestToken);
        if (checkpoint == null || !checkpoint.isEmpty() && !checkpoint.equals("read-v1")
                || maxOperations < 1 || maxOperations > 128 || maxMillis < 1 || maxMillis > 30000) throw invalid();
        if (!known()) return evidence(State.INCOMPLETE, 0, null);
        long deadline = System.nanoTime() + maxMillis * 1000000L;
        if (manifest.runtimes().size() > maxOperations) return evidence(State.DRAINING, 0, null);
        seed(lease, generation, manifestToken);
        if (System.nanoTime() >= deadline) return evidence(State.DRAINING, 0, null);
        State mine = acknowledge(lease, generation, manifestToken, maxMillis);
        if (mine == State.INCOMPLETE) return evidence(State.INCOMPLETE, 0, null);
        Evidence result = status(lease, generation, manifestToken);
        return System.nanoTime() >= deadline ? evidence(State.DRAINING, result.acknowledged(), null) : result;
    }

    /**
     * Runtime-control hook, called on that actual process, not by writing an acknowledgement
     * on its behalf. Repeat before freshness expires. No thread, Pub/Sub proof or SQL is hidden here.
     */
    public State acknowledge(Lease lease, long generation, String manifestToken, int maxMillis) {
        validate(lease, generation, manifestToken);
        if (maxMillis < 1 || maxMillis > 30000) throw invalid();
        if (!known()) return State.INCOMPLETE;
        long deadline = System.nanoTime() + maxMillis * 1000000L;
        var drained = safe(() -> local.closeAndDrain(lease, generation));
        if (!drained.drained()) { revoke(lease,generation,manifestToken); return State.DRAINING; }
        Probe external = safe(() -> probe.inspect(lease, generation, manifestToken));
        if (external == null || external.state() == State.INCOMPLETE) { revoke(lease,generation,manifestToken); return State.INCOMPLETE; }
        if (external.state() != State.CLOSED || System.nanoTime() >= deadline) {
            revoke(lease,generation,manifestToken); return State.DRAINING;
        }
        var args = args(lease, generation, manifestToken);
        args.add(local.runtimeId().toString());
        args.add(external.token());
        eval(lease, REQUEST_CHECK + """
            if redis.call('HGET',KEYS[3],'m:'..ARGV[8])~='1' then return redis.error_reply('READ_MEMBERSHIP_UNKNOWN') end
            local time=redis.call('TIME')
            local now=tonumber(time[1])*1000+math.floor(tonumber(time[2])/1000)
            redis.call('HSET',KEYS[3],'a:'..ARGV[8],ARGV[9],'t:'..ARGV[8],tostring(now))
            return 1
            """, args);
        return System.nanoTime() >= deadline ? State.DRAINING : State.CLOSED;
    }

    /** Native bounded control transport. Invoke from each runtime's existing administrative loop. */
    public State poll(int maxMillis) {
        if (maxMillis < 1 || maxMillis > 30000) throw invalid();
        if (!com.reactor.cachedb.core.tenant.CacheTenantContext.isAdministrative())
            throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);
        if (!known()) return State.INCOMPLETE;
        String generationKey=barrier.generationKey(key);
        Object reply=safe(()->redis.eval("""
            if redis.call('TYPE',KEYS[3]).ok=='none' then return false end
            if redis.call('TYPE',KEYS[3]).ok~='hash' or redis.call('HLEN',KEYS[3])>394
              or redis.call('HGET',KEYS[3],'schema')~='1'
              or redis.call('HGET',KEYS[3],'manifest')~=ARGV[1]
              or redis.call('HGET',KEYS[3],'roster')~=ARGV[2]
              or redis.call('HGET',KEYS[1],'state')~='CLOSED'
              or redis.call('PTTL',KEYS[2])<=0 then return redis.error_reply('READ_CLOSURE_UNKNOWN') end
            local current=string.match(redis.call('INFO','SERVER'),'run_id:([0-9a-f]+)')
            if not current or redis.call('HGET',KEYS[1],'run_id')~=current
              or redis.call('HGET',KEYS[3],'run_id')~=current then return redis.error_reply('READ_RUN_CHANGED') end
            local fields={'generation','operation','owner','epoch','lease_seconds'}
            local result={}
            for _,field in ipairs(fields) do
              local value=redis.call('HGET',KEYS[3],field)
              if not value or #value>64 then return redis.error_reply('READ_SCHEMA_UNKNOWN') end
              result[#result+1]=value
            end
            return result
            """,List.of(generationKey,barrier.leaseKey(key),journal),List.of(manifest.token(),roster)));
        if (reply==null) return State.DRAINING;
        if (!(reply instanceof List<?> values) || values.size()!=5) throw incomplete();
        try {
            long generation=Long.parseLong((String)values.get(0));
            Lease lease=new Lease(key,UUID.fromString((String)values.get(1)),UUID.fromString((String)values.get(2)),
                    Long.parseLong((String)values.get(3)),Integer.parseInt((String)values.get(4)));
            return acknowledge(lease,generation,manifest.token(),maxMillis);
        } catch (RuntimeException malformed) {throw incomplete();}
    }

    @Override public void requireClosed(Lease lease, long generation, String manifestToken, Evidence evidence) {
        validate(lease, generation, manifestToken);
        if (!known() || evidence == null || evidence.state() != State.CLOSED
                || evidence.registered() != manifest.runtimes().size()
                || evidence.acknowledged() != evidence.registered())
            throw incomplete();
        if (acknowledge(lease, generation, manifestToken, 5000) != State.CLOSED) throw incomplete();
        Evidence live = status(lease, generation, manifestToken);
        if (live.state() != State.CLOSED || !live.token().equals(evidence.token())) throw incomplete();
    }

    @Override public void release(Lease lease) {
        if (lease == null || !lease.key().equals(key)) throw invalid();
        // No private executor or lease is held. Retain the one bounded journal per pair;
        // a replacement lease reseeds it. Unknown journal fields must NEVER be deleted here.
        // This method neither opens admission nor removes generation/security metadata.
    }

    private void revoke(Lease lease,long generation,String manifestToken) {
        var args=args(lease,generation,manifestToken);
        args.add(local.runtimeId().toString());
        eval(lease,REQUEST_CHECK+"return redis.call('HDEL',KEYS[3],'a:'..ARGV[8],'t:'..ARGV[8])",args);
    }

    private boolean known() {
        return manifest.complete() && !manifest.runtimes().isEmpty()
                && manifest.runtimes().contains(local.runtimeId()) && probe != null;
    }

    private void seed(Lease lease, long generation, String manifestToken) {
        var args = args(lease, generation, manifestToken);
        args.add(Integer.toString(lease.seconds()));
        for (UUID member : manifest.runtimes()) args.add(member.toString());
        eval(lease, """
            local kind=redis.call('TYPE',KEYS[3]).ok
            if kind~='none' and (kind~='hash' or redis.call('HGET',KEYS[3],'schema')~='1')
              then return redis.error_reply('READ_SCHEMA_UNKNOWN') end
            if kind=='hash' then
              local fields=redis.call('HKEYS',KEYS[3])
              if #fields>394 then return redis.error_reply('READ_SCHEMA_UNKNOWN') end
              local fixed={schema=true,generation=true,operation=true,owner=true,epoch=true,manifest=true,roster=true,run_id=true,required=true,lease_seconds=true}
              for i=9,#ARGV do fixed['m:'..ARGV[i]]=true;fixed['a:'..ARGV[i]]=true;fixed['t:'..ARGV[i]]=true end
              for _,field in ipairs(fields) do
                if not fixed[field] then return redis.error_reply('READ_SCHEMA_UNKNOWN') end
              end
              for _,field in ipairs({'generation','operation','owner','epoch','manifest','roster','run_id','required','lease_seconds'}) do
                local value=redis.call('HGET',KEYS[3],field)
                if not value or #value>128 then return redis.error_reply('READ_SCHEMA_UNKNOWN') end
              end
              local same=redis.call('HGET',KEYS[3],'operation')==ARGV[2]
                and redis.call('HGET',KEYS[3],'owner')==ARGV[3] and redis.call('HGET',KEYS[3],'epoch')==ARGV[4]
              if same and (redis.call('HGET',KEYS[3],'manifest')~=ARGV[6]
                or redis.call('HGET',KEYS[3],'roster')~=ARGV[7])
                then return redis.error_reply('READ_MANIFEST_CHANGED') end
              if not same then redis.call('DEL',KEYS[3]) end
            end
            redis.call('HSET',KEYS[3],'schema','1','generation',ARGV[1],'operation',ARGV[2],
              'owner',ARGV[3],'epoch',ARGV[4],'manifest',ARGV[6],'roster',ARGV[7],
              'run_id',redis.call('HGET',KEYS[1],'run_id'),'required',tostring(#ARGV-8),'lease_seconds',ARGV[8])
            for i=9,#ARGV do redis.call('HSET',KEYS[3],'m:'..ARGV[i],'1') end
            return 1
            """, args);
    }

    private static final String REQUEST_CHECK = """
        if redis.call('TYPE',KEYS[3]).ok~='hash' or redis.call('HLEN',KEYS[3])>394
          or redis.call('HGET',KEYS[3],'schema')~='1'
          or redis.call('HGET',KEYS[3],'generation')~=ARGV[1]
          or redis.call('HGET',KEYS[3],'operation')~=ARGV[2]
          or redis.call('HGET',KEYS[3],'owner')~=ARGV[3]
          or redis.call('HGET',KEYS[3],'epoch')~=ARGV[4]
          or redis.call('HGET',KEYS[3],'manifest')~=ARGV[6]
          or redis.call('HGET',KEYS[3],'roster')~=ARGV[7]
          or redis.call('HGET',KEYS[3],'run_id')~=redis.call('HGET',KEYS[1],'run_id')
          then return redis.error_reply('READ_CLOSURE_UNKNOWN') end
        """;

    private static final String STATUS = """
            if redis.call('HGET',KEYS[3],'required')~=tostring(#ARGV-8) then return redis.error_reply('READ_MEMBERSHIP_UNKNOWN') end
            local fields=redis.call('HKEYS',KEYS[3])
            local allowed={schema=true,generation=true,operation=true,owner=true,epoch=true,manifest=true,roster=true,run_id=true,required=true,lease_seconds=true}
            for i=9,#ARGV do allowed['m:'..ARGV[i]]=true;allowed['a:'..ARGV[i]]=true;allowed['t:'..ARGV[i]]=true end
            for _,field in ipairs(fields) do if not allowed[field] then return redis.error_reply('READ_SCHEMA_UNKNOWN') end end
            local time=redis.call('TIME')
            local now=tonumber(time[1])*1000+math.floor(tonumber(time[2])/1000)
            local count=0;local proof=redis.call('HGET',KEYS[1],'run_id')
            for i=9,#ARGV do
              if redis.call('HGET',KEYS[3],'m:'..ARGV[i])~='1' then return redis.error_reply('READ_MEMBERSHIP_UNKNOWN') end
              local stamp=tonumber(redis.call('HGET',KEYS[3],'t:'..ARGV[i]) or '-1')
              local token=redis.call('HGET',KEYS[3],'a:'..ARGV[i])
              if stamp>now or stamp<0 or now-stamp>tonumber(ARGV[8]) or not token or #token~=64 or not string.match(token,'^[0-9a-f]+$') then
                proof=proof..':missing'
              else count=count+1;proof=proof..':'..ARGV[i]..':'..token end
            end
            """;

    /** Trusted owner-only program; roster, lease and the actual fresh drain ACKs share its atomic command. */
    Object ownerClosed(RedisTenantRuntimeControl control, Lease lease, long generation, String manifestToken,
            String program, List<String> mutationKeys, List<String> mutationArgs) {
        validate(lease, generation, manifestToken);
        if (!known() || !control.approval().manifestToken().equals(manifestToken)
                || !new HashSet<>(control.approval().members()).equals(new HashSet<>(manifest.runtimes())))
            throw incomplete();
        var allKeys = new ArrayList<>(keys(lease)); allKeys.addAll(mutationKeys);
        var allArgs = args(lease, generation, manifestToken);
        allArgs.add(Integer.toString(freshnessMillis));
        for (UUID member : manifest.runtimes()) allArgs.add(member.toString());
        int offset = allArgs.size() + 1; allArgs.addAll(mutationArgs);
        String split = "local ownerArgs={unpack(ARGV," + offset + ")}; local ARGV={unpack(ARGV,1,"
                + (offset - 1) + ")}\n";
        return control.initialClosed(lease, generation, split + REQUEST_CHECK + STATUS + """
            if count~=#ARGV-8 then return redis.error_reply('READ_CLOSURE_STALE') end
            local KEYS={unpack(KEYS,4)}; local ARGV=ownerArgs
            """ + program, allKeys, allArgs);
    }

    private Evidence status(Lease lease, long generation, String manifestToken) {
        var args = args(lease, generation, manifestToken);
        args.add(Integer.toString(freshnessMillis));
        for (UUID member : manifest.runtimes()) args.add(member.toString());
        Object reply = eval(lease, REQUEST_CHECK + STATUS + "return {count,proof}", args);
        if (!(reply instanceof List<?> values) || values.size()!=2 || !(values.get(0) instanceof Number number)
                || !(values.get(1) instanceof String proof) || proof.length()>16384) throw incomplete();
        int count = number.intValue();
        if (count<0 || count>manifest.runtimes().size()) throw incomplete();
        if (count!=manifest.runtimes().size()) return evidence(State.DRAINING,count,null);
        String token = digest(List.of(key.scopeToken(),key.tenantToken(),lease.operationId().toString(),
                lease.owner().toString(),Long.toString(lease.epoch()),Long.toString(generation),manifestToken,roster,proof));
        return evidence(State.CLOSED,count,token);
    }

    private Object eval(Lease lease, String program, List<String> args) {
        String script=RedisGenerationBarrier.closedCheckLua()+program;
        RedisProtectionArguments.validate(script,keys(lease),args);
        return safe(()->redis.eval(script,keys(lease),args));
    }
    private List<String> keys(Lease lease) {
        var keys=new ArrayList<>(barrier.fencedKeys(lease));keys.add(journal);return keys;
    }
    private ArrayList<String> args(Lease lease,long generation,String token) {
        var args=new ArrayList<>(barrier.fencedArgs(lease,generation));args.add(token);args.add(roster);return args;
    }
    private Evidence evidence(State state,int count,String token) {
        return new Evidence(state,"read-v1",token,manifest.runtimes().size(),count);
    }
    private void validate(Lease lease,long generation,String token) {
        if(lease==null || !lease.key().equals(key) || generation<=0) throw invalid();
        ProtectionKey.token(token);
        if(!manifest.token().equals(token)) throw incomplete();
    }
    private static String digest(List<String> values) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String value:values) {
                byte[] encoded=value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(encoded.length).array());
                digest.update(encoded);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static <V> V safe(java.util.function.Supplier<V> action) {
        try {return action.get();}
        catch(ProtectionException known) {
            if (known.code()==ProtectionException.Code.LEASE_LOST || known.code()==ProtectionException.Code.WRITE_FENCED
                    || known.code()==ProtectionException.Code.INCOMPLETE_INVENTORY) throw known;
            throw incomplete();
        }
        catch(RuntimeException unavailable) {throw incomplete();}
    }
    private static ProtectionException incomplete() {return new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY);}
    private static ProtectionException invalid() {return new ProtectionException(ProtectionException.Code.INVALID_REQUEST);}
}

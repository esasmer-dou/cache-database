package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Atomic approval of the complete bounded standard-root enrollment set; never source ownership or retirement. */
public final class RedisTenantRuntimeControl {
    public record ClosedPair(Lease lease, long generation) {
        public ClosedPair {
            if (lease == null || generation < 1) throw invalid();
        }
        @Override public String toString() { return "ClosedRuntimePair[redacted]"; }
    }

    /** Explicit deployment approval; members are actual local read-fence incarnations, not host names. */
    public record ApprovedRoster(String approvalToken, String manifestToken, String coverageToken,
            List<UUID> members, List<ProtectionKey> pairs) {
        public ApprovedRoster {
            ProtectionKey.token(approvalToken); ProtectionKey.token(manifestToken); ProtectionKey.token(coverageToken);
            if (members == null || members.isEmpty() || members.size() > 128
                    || pairs == null || pairs.isEmpty() || pairs.size() > 64) throw invalid();
            var runtimes = new TreeSet<UUID>();
            for (var member : members) if (member == null || !runtimes.add(member)) throw invalid();
            var keys = new TreeSet<ProtectionKey>();
            for (var pair : pairs) if (pair == null || !keys.add(pair)) throw invalid();
            members = List.copyOf(runtimes); pairs = List.copyOf(keys);
        }
        @Override public String toString() { return "ApprovedRuntimeRoster[redacted]"; }
    }

    private static final String CHECK = """
        local function check(KEYS,ARGV)
        """ + RedisGenerationBarrier.closedCheckLua() + "\nreturn 1 end\n";
    private static final String VERIFY = """
        local function verify(key,offset)
          if redis.call('TYPE',key).ok~='hash' or redis.call('PTTL',key)~=-1
            or redis.call('HLEN',key)~=tonumber(ARGV[offset])+1 then return redis.error_reply('RUNTIME_ROSTER_UNKNOWN') end
          local run=string.match(redis.call('INFO','SERVER'),'run_id:([0-9a-f]+)')
          if not run or redis.call('HSTRLEN',key,'run_id')~=40
            or redis.call('HGET',key,'run_id')~=run then return redis.error_reply('RUNTIME_RUN_CHANGED') end
          for i=1,tonumber(ARGV[offset]) do
            local field,value=ARGV[offset+2*i-1],ARGV[offset+2*i]
            if redis.call('HSTRLEN',key,field)>128 or redis.call('HGET',key,field)~=value
              then return redis.error_reply('RUNTIME_ROSTER_CHANGED') end
          end
          cachedb_runtime_admit(key,ARGV[offset+2*tonumber(ARGV[offset])+1])
          return 1
        end
        """;
    private final RedisClientOperations redis;
    private final RedisGenerationBarrier barrier;
    private final String scope, rosterKey;
    private final UUID local;
    private final ReentrantLock configuration = new ReentrantLock();
    private volatile ApprovedRoster approved;
    private List<String> fields;

    public RedisTenantRuntimeControl(RedisClientOperations redis, RedisKeyStrategy keys,
            RedisTenantProducer producer) {
        if (redis == null || keys == null || producer == null || !producer.belongsToRuntime(redis, keys)) throw invalid();
        this.redis = redis; barrier = producer.barrier(); scope = ProtectionKey.hash(producer.namespace());
        if (barrier.admission() == null) throw incomplete();
        local = producer.runtimeId();
        rosterKey = rosterKey(keys,producer.namespace());
    }

    /**
     * All catalogue pairs are checked atomically using the canonical CLOSED lease prelude.
     * A restart/new member cannot edit an existing approval. No expiry, eviction or retirement exists.
     * This validates Redis maintenance authority only; it never manufactures a SQL migration receipt.
     */
    public void sealClosed(ApprovedRoster approval, List<ClosedPair> closed) {
        admin();
        configuration.lock();
        try { sealClosedLocked(approval, closed); }
        finally { configuration.unlock(); }
    }

    private void sealClosedLocked(ApprovedRoster approval, List<ClosedPair> closed) {
        if (approval == null || closed == null || closed.size() != approval.pairs().size()
                || !approval.members().contains(local)) throw invalid();
        for (var key : approval.pairs()) if (!scope.equals(key.scopeToken())) throw invalid();
        if (approved != null && !approved.equals(approval)) throw incomplete();
        var leases = new TreeMap<ProtectionKey, ClosedPair>();
        for (var pair : closed) if (pair == null || leases.putIfAbsent(pair.lease().key(), pair) != null) throw invalid();
        if (!List.copyOf(leases.keySet()).equals(approval.pairs())) throw invalid();
        var expected = fields(approval, local);
        var keys = new ArrayList<String>(); keys.add(rosterKey);
        var args = new ArrayList<String>(); args.add(Integer.toString(closed.size()));
        for (var pair : leases.values()) {
            keys.addAll(barrier.fencedKeys(pair.lease()));
            args.addAll(barrier.fencedArgs(pair.lease(), pair.generation()));
        }
        int offset = args.size() + 1; args.addAll(expected);
        String script = RedisTenantVersionAllocator.library() + CHECK + VERIFY + """
            local count=tonumber(ARGV[1])
            for i=1,count do
              local start=2+(i-1)*5
              local checked=check({KEYS[2*i],KEYS[2*i+1]}, {ARGV[start],ARGV[start+1],ARGV[start+2],ARGV[start+3],ARGV[start+4]})
              if checked~=1 then return checked end
            end
            local offset=2+count*5
            local enrolled,required,pairCount,state=cachedb_runtime_counts(KEYS[1])
            if state=='COLLECTING' then
              local desired={}
              for i=1,tonumber(ARGV[offset]) do desired[ARGV[offset+2*i-1]]=ARGV[offset+2*i] end
              if enrolled~=tonumber(desired.enrolled) or required~=0 or pairCount~=0
                then return redis.error_reply('RUNTIME_ROSTER_INCOMPLETE') end
              for field,value in pairs(desired) do
                if string.sub(field,1,2)=='e:' and redis.call('HGET',KEYS[1],field)~=value
                  then return redis.error_reply('RUNTIME_ROSTER_INCOMPLETE') end
              end
              for i=1,tonumber(ARGV[offset]) do redis.call('HSET',KEYS[1],ARGV[offset+2*i-1],ARGV[offset+2*i]) end
            elseif state~='SEALED' then return redis.error_reply('RUNTIME_REVIEW_REQUIRED')
            end
            return verify(KEYS[1],offset)
            """;
        if (offset != 2 + closed.size() * 5) throw invalid();
        call(script, keys, args);
        fields = expected; approved = approval;
    }

    public ApprovedRoster approval() {
        var value = approved; if (value == null) throw incomplete(); return value;
    }
    /** Exact owned security-store key for administrative manifest registration; never derive it by parsing data keys. */
    public String rosterKey() { return rosterKey; }
    /** Canonical retained-store descriptor only; knowing its key grants no enrollment or creation authority. */
    public static String rosterKey(RedisKeyStrategy keys,String namespace) {
        Objects.requireNonNull(keys);Objects.requireNonNull(namespace);
        return keys.namespacePrefix(namespace)+":security:runtime-roster";
    }

    /** Bounded exact validation; no SCAN/HGETALL, TTL refresh or historical-member omission. */
    public void requireSealed() {
        approval();
        call(RedisTenantVersionAllocator.library() + VERIFY + "return verify(KEYS[1],1)", List.of(rosterKey), fields);
    }

    /** Package-private trusted INITIAL programs only; checks roster and original CLOSED lease in the SAME command. */
    Object initialClosed(com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease lease,long generation,
            String body,List<String> mutationKeys,List<String> mutationArgs) {
        admin();var approval=approval();
        if(!approval.pairs().contains(lease.key()))throw incomplete();
        var allKeys=new ArrayList<>(barrier.fencedKeys(lease));allKeys.add(rosterKey);allKeys.addAll(mutationKeys);
        var args=new ArrayList<>(barrier.fencedArgs(lease,generation));args.addAll(fields);
        int start=args.size()+1;args.addAll(mutationArgs);
        String script=RedisTenantVersionAllocator.library()+CHECK+VERIFY+"""
            local checked=check({KEYS[1],KEYS[2]},{ARGV[1],ARGV[2],ARGV[3],ARGV[4],ARGV[5]})
            if checked~=1 then return checked end
            local approved=verify(KEYS[3],6)
            if approved~=1 then return approved end
            local KEYS={unpack(KEYS,4)}
            """+"local values={}; for i="+start+",#ARGV do values[#values+1]=ARGV[i] end; local ARGV=values\n"+body;
        RedisProtectionArguments.validateInitial(script,allKeys,args);
        try {redis.requireSameSlot(allKeys);return redis.eval(script,allKeys,args);}
        catch(RuntimeException unsafe){throw incomplete();}
    }

    Object initialObserve(String body,List<String> mutationKeys,List<String> mutationArgs) {
        admin();approval();var allKeys=new ArrayList<String>();allKeys.add(rosterKey);allKeys.addAll(mutationKeys);
        var args=new ArrayList<>(fields);int start=args.size()+1;args.addAll(mutationArgs);
        String script=RedisTenantVersionAllocator.library()+VERIFY+"""
            local approved=verify(KEYS[1],1)
            if approved~=1 then return approved end
            local KEYS={unpack(KEYS,2)}
            """+"local values={}; for i="+start+",#ARGV do values[#values+1]=ARGV[i] end; local ARGV=values\n"+body;
        RedisProtectionArguments.validateInitial(script,allKeys,args);
        try {redis.requireSameSlot(allKeys);return redis.eval(script,allKeys,args);}
        catch(RuntimeException unsafe){throw incomplete();}
    }

    private static List<String> fields(ApprovedRoster approval, UUID local) {
        var expected = new TreeMap<String, String>();
        expected.put("schema", "2"); expected.put("state", "SEALED"); expected.put("approval", approval.approvalToken());
        expected.put("manifest", approval.manifestToken()); expected.put("coverage", approval.coverageToken());
        expected.put("required", Integer.toString(approval.members().size()));
        expected.put("enrolled", Integer.toString(approval.members().size()));
        expected.put("pairs", Integer.toString(approval.pairs().size()));
        for (var member : approval.members()) {
            expected.put("e:" + member, "1"); expected.put("m:" + member, "1");
        }
        for (var pair : approval.pairs()) expected.put("p:" + pair.tenantToken(), "1");
        var values = new ArrayList<String>(); values.add(Integer.toString(expected.size()));
        expected.forEach((field, value) -> { values.add(field); values.add(value); });
        values.add(local.toString());
        return List.copyOf(values);
    }

    private void call(String script, List<String> keys, List<String> args) {
        try {
            redis.requireSameSlot(keys);
            Object result = redis.eval(script, keys, args);
            if (!(result instanceof Number number) || number.longValue() != 1) throw incomplete();
        }
        catch (RuntimeException unsafe) { throw incomplete(); }
    }

    static String digest(List<String> fields) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    static void admin() {
        if (!CacheTenantContext.isAdministrative()) throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);
    }
    static ProtectionException invalid() { return new ProtectionException(ProtectionException.Code.INVALID_REQUEST); }
    static ProtectionException incomplete() { return new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY); }
}

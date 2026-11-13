package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.codec.Utf8Length;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.protection.ProtectionKey;
import com.reactor.cachedb.core.protection.ProtectionException;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Bounded Redis-resident maintenance, not a business write or source migration.
 * The supplied fence MUST be the actual enrolled runtime fence, not a private substitute.
 * The supplied wire MUST address the same authenticated primary/database as the runtime.
 */
public final class RedisReencryptionRunner<T, ID> {
    public enum Scope { REDIS_RESIDENT, SOURCE }
    public enum Status { MORE, COMPLETE, COMPLETE_WITH_GAPS }
    public enum Cleanup { DRAINING, CLEARED, INCOMPLETE }
    public enum Code {
        INVALID_REQUEST, UNBOUND_MAPPING, UNSUPPORTED_SOURCE, BUSY, GENERATION_REJECTED,
        CURSOR_MISMATCH, INPUT_LIMIT, STORAGE_UNAVAILABLE
    }
    public static final class Failure extends RuntimeException {
        private final Code code;
        Failure(Code code) { super(code.name(), null, false, false); this.code = code; }
        public Code code() { return code; }
    }
    /** Counts are examined occurrences, not distinct IDs: Redis SCAN may repeat a key. */
    public record Step(Status status, int examinedThisCall, long examined, long rewritten,
            long conflicts, long foreign, long missing, long blocked) {}

    private static final int MAX_ROWS = 10_000, MAX_PAYLOAD = 262_144, MAX_FRAMES = 32;
    private static final long MAX_NANOS = 5_000_000_000L;
    private static final String LEASE_MILLIS = "30000", CURSOR_SECONDS = "604800";
    private static final BoundedRedisWire.Limits SCAN_LIMIT =
            new BoundedRedisWire.Limits(65536, 8192, 259, 16384);
    private static final BoundedRedisWire.Limits IO_LIMIT = BoundedRedisWire.Limits.entries();
    private static final String ORIGINAL = RedisTenantVersionAllocator.library() + """
        if redis.call('HGET',KEYS[1],'state')~='OPEN'
          or redis.call('HGET',KEYS[1],'generation')~=ARGV[1]
          then return {'~R1','GENERATION_REJECTED'} end
        cachedb_version_assert(KEYS[1],KEYS[2],KEYS[3],ARGV[1],cachedb_version_run_id())
        local function kind(k,t)
          local actual=redis.call('TYPE',k).ok
          return actual=='none' or actual==t
        end
        if not kind(KEYS[4],'hash') or redis.call('HLEN',KEYS[4])>16
          then return {'~R1','CURSOR_MISMATCH'} end
        local fields={'fmt','target','gen','topo','cursor','pending','offset','done',
          'total','updated','conflicts','foreign','missing','blocked','token','until'}
        local expected={}
        for _,f in ipairs(fields) do expected[f]=true end
        for _,f in ipairs(redis.call('HKEYS',KEYS[4])) do
          if not expected[f] then return {'~R1','CURSOR_MISMATCH'} end
        end
        for _,f in ipairs(fields) do
          local limit=f=='pending' and 90000 or 128
          if redis.call('HSTRLEN',KEYS[4],f)>limit then return {'~R1','INPUT_LIMIT'} end
        end
        local tm=redis.call('TIME')
        local now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000)
        local function ttl()
          redis.call('EXPIRE',KEYS[4],ARGV[6])
        end
        local function stamp()
          if redis.call('HGET',KEYS[4],'fmt')~='1'
            or redis.call('HGET',KEYS[4],'target')~=ARGV[3]
            or redis.call('HGET',KEYS[4],'gen')~=ARGV[1]
            or redis.call('HGET',KEYS[4],'topo')~=ARGV[4]
            then return false end
          return true
        end
        local function owned()
          return stamp() and redis.call('HGET',KEYS[4],'token')==ARGV[2]
            and tonumber(redis.call('HGET',KEYS[4],'until') or '0')>now
        end
        local function renew()
          redis.call('HSET',KEYS[4],'until',tostring(now+tonumber(ARGV[5])))
          ttl()
        end
        local function snapshot()
          return redis.call('HMGET',KEYS[4],'cursor','pending','offset','done','total',
            'updated','conflicts','foreign','missing','blocked')
        end
        """;
    private static final String ACQUIRE = """
        if redis.call('EXISTS',KEYS[4])==0 then
          redis.call('HSET',KEYS[4],'fmt','1','target',ARGV[3],'gen',ARGV[1],'topo',ARGV[4],
            'cursor','0','pending','','offset','0','done','0','total','0','updated','0',
            'conflicts','0','foreign','0','missing','0','blocked','0','token','','until','0')
        elseif not stamp() then return {'~R1','CURSOR_MISMATCH'} end
        if tonumber(redis.call('HGET',KEYS[4],'until') or '0')>now then
          return {'~R1','BUSY'}
        end
        redis.call('HSET',KEYS[4],'token',ARGV[2]); renew()
        return snapshot()
        """;
    private static final String OWNED = """
        if not owned() then return {'~R1','BUSY'} end
        """;
    private static final String STAGE = OWNED + """
        if redis.call('HGET',KEYS[4],'pending')~='' or redis.call('HGET',KEYS[4],'cursor')~=ARGV[7]
          or redis.call('HGET',KEYS[4],'done')~='0' then return {'~R1','CURSOR_MISMATCH'} end
        redis.call('HSET',KEYS[4],'pending',ARGV[8],'offset','0'); renew()
        return 1
        """;
    private static final String READ = OWNED + """
        for i=5,9 do if not kind(KEYS[i],'string') then return {'blocked'} end end
        if redis.call('STRLEN',KEYS[7])>256 then return {'blocked'} end
        local owner=redis.call('GET',KEYS[7])
        if owner and owner~=ARGV[7] then return {'foreign'} end
        if not owner then return {'blocked'} end
        if redis.call('EXISTS',KEYS[8])==1 or redis.call('EXISTS',KEYS[5])==0 then return {'missing'} end
        if redis.call('STRLEN',KEYS[5])>262144 or redis.call('STRLEN',KEYS[6])>19
          or redis.call('STRLEN',KEYS[9])>16384 then return {'blocked'} end
        local version=redis.call('GET',KEYS[6])
        if not version or not string.match(version,'^[1-9][0-9]*$') then return {'blocked'} end
        renew()
        return {'owned',redis.call('GET',KEYS[5]),version,redis.call('GET',KEYS[9]) or ''}
        """;
    /**
     * Advance and payload replacement form ONE Redis transaction. A lost response must not
     * leave a rewritten row paired with its old cursor. Preflight every possible schema error
     * before SET: a Lua error does not roll back earlier Redis writes.
     */
    private static final String COMMIT = OWNED + """
        if redis.call('HGET',KEYS[4],'offset')~=ARGV[7]
          or redis.call('HGET',KEYS[4],'pending')=='' then return {'~R1','CURSOR_MISMATCH'} end
        local outcome=ARGV[11]
        local counters={'total','updated','conflicts','foreign','missing','blocked'}
        for _,f in ipairs(counters) do
          local n=redis.call('HGET',KEYS[4],f)
          if not n or not string.match(n,'^%d+$') or #n>12 or tonumber(n)>=999999999999
            then return {'~R1','INPUT_LIMIT'} end
        end
        if outcome=='updated' then
          for i=5,9 do
            if not kind(KEYS[i],'string') then outcome='blocked' end
          end
          if outcome=='updated' then
            if redis.call('STRLEN',KEYS[5])>262144 or redis.call('STRLEN',KEYS[6])>19
              or redis.call('STRLEN',KEYS[7])>256 or redis.call('STRLEN',KEYS[9])>16384
              then outcome='blocked'
            elseif redis.call('GET',KEYS[7])~=ARGV[12] or redis.call('EXISTS',KEYS[8])==1
              or redis.call('GET',KEYS[6])~=ARGV[13] or redis.call('GET',KEYS[5])~=ARGV[14]
              or (redis.call('GET',KEYS[9]) or '')~=ARGV[16]
              then outcome='conflicts' end
          end
        end
        if not redis.acl_check_cmd then return {'~R1','STORAGE_UNAVAILABLE'} end
        for _,f in ipairs(counters) do
          if not redis.acl_check_cmd('HINCRBY',KEYS[4],f,1)
            then return {'~R1','STORAGE_UNAVAILABLE'} end
        end
        if not redis.acl_check_cmd('HINCRBY',KEYS[4],'offset',1)
          or not redis.acl_check_cmd('HSET',KEYS[4],'pending','','offset','0','cursor',ARGV[9],
            'done',ARGV[10],'until',tostring(now+tonumber(ARGV[5])))
          or not redis.acl_check_cmd('EXPIRE',KEYS[4],ARGV[6])
          or (outcome=='updated' and not redis.acl_check_cmd('SET',KEYS[5],ARGV[15],'XX','KEEPTTL'))
          then return {'~R1','STORAGE_UNAVAILABLE'} end
        if outcome=='updated' then
          if not redis.call('SET',KEYS[5],ARGV[15],'XX','KEEPTTL') then outcome='conflicts' end
        end
        redis.call('HINCRBY',KEYS[4],'total',1)
        redis.call('HINCRBY',KEYS[4],outcome,1)
        if tonumber(ARGV[7])+1==tonumber(ARGV[8]) then
          redis.call('HSET',KEYS[4],'pending','','offset','0','cursor',ARGV[9],'done',ARGV[10])
        else redis.call('HINCRBY',KEYS[4],'offset',1) end
        renew()
        return outcome
        """;
    private static final String EMPTY = OWNED + """
        if redis.call('HGET',KEYS[4],'pending')=='' then return {'~R1','CURSOR_MISMATCH'} end
        redis.call('HSET',KEYS[4],'pending','','offset','0','cursor',ARGV[7],'done',ARGV[8])
        renew(); return 1
        """;
    private static final String RELEASE = OWNED + """
        redis.call('HSET',KEYS[4],'token','','until','0'); ttl(); return 1
        """;
    private static final String RESET = """
        if redis.call('EXISTS',KEYS[4])==1 and (redis.call('HLEN',KEYS[4])~=16
          or redis.call('HGET',KEYS[4],'fmt')~='1') then return {'~R1','CURSOR_MISMATCH'} end
        if tonumber(redis.call('HGET',KEYS[4],'until') or '0')>now
          then return {'~R1','BUSY'} end
        redis.call('DEL',KEYS[4]); return 1
        """;

    private final EntityMetadata<T, ID> metadata;
    private final EntityCodec<T> codec;
    private final RedisClientOperations redis;
    private final RedisKeyStrategy keys;
    private final RedisGenerationBarrier barrier;
    private final RedisTenantReadFence fence;
    private final BoundedRedisWire wire;
    private final RedisPurgeScanner topology;
    private final Map<String, String> prefixes;
    private final String target;
    private final Semaphore admission = new Semaphore(1);
    private final Runnable afterCommit;

    public RedisReencryptionRunner(EntityMetadata<T, ID> metadata, EntityCodec<T> runtimeCodec,
            CacheKeyProvider provider, RedisClientOperations redis, RedisKeyStrategy keys,
            RedisGenerationBarrier barrier, RedisTenantReadFence enrolledFence, BoundedRedisWire wire) {
        this(metadata, runtimeCodec, provider, redis, keys, barrier, enrolledFence, wire, () -> {});
    }

    // Deterministic crash-after-commit seam; never part of the public runtime contract.
    RedisReencryptionRunner(EntityMetadata<T, ID> metadata, EntityCodec<T> runtimeCodec,
            CacheKeyProvider provider, RedisClientOperations redis, RedisKeyStrategy keys,
            RedisGenerationBarrier barrier, RedisTenantReadFence enrolledFence, BoundedRedisWire wire,
            Runnable afterCommit) {
        try {
            this.metadata = Objects.requireNonNull(metadata);
            if (!metadata.tenantPurgeEnabled() || metadata.tenantFieldName() == null
                    || metadata.encryptedColumns().isEmpty()) throw fail(Code.UNBOUND_MAPPING);
            EncryptedStorageChecks.validateMetadata(metadata);
            CacheKeyProvider pinned = pin(provider, metadata);
            this.codec = Objects.requireNonNull(runtimeCodec).bindEncryption(pinned);
            EncryptedStorageChecks.requireBound(metadata, codec);
            if (!codec.encryptedMapping()) throw fail(Code.UNBOUND_MAPPING);
            this.redis = Objects.requireNonNull(redis);
            this.keys = Objects.requireNonNull(keys);
            this.barrier = Objects.requireNonNull(barrier);
            this.fence = Objects.requireNonNull(enrolledFence);
            this.wire = Objects.requireNonNull(wire);
            this.topology = new RedisPurgeScanner(wire, redis, keys, barrier, metadata.redisNamespace());
            this.prefixes = keys.entityKeyPrefixes(List.of(metadata.redisNamespace()));
            this.target = target(metadata, pinned);
            this.afterCommit = Objects.requireNonNull(afterCommit);
        } catch (RuntimeException unsafe) { throw fail(Code.UNBOUND_MAPPING); }
    }

    /** Lexically scoped tenant; administrative callers must use the explicit tenant overload. */
    public Step reencrypt(Class<?> entityClass, int maxRows) {
        String tenant = CacheTenantContext.currentTenant().orElseThrow(() -> fail(Code.INVALID_REQUEST));
        return reencrypt(entityClass, tenant, maxRows, Scope.REDIS_RESIDENT);
    }

    public Step reencrypt(Class<?> entityClass, String tenant, int maxRows) {
        return reencrypt(entityClass, tenant, maxRows, Scope.REDIS_RESIDENT);
    }

    public Step reencrypt(Class<?> entityClass, String tenant, int maxRows, Scope scope) {
        validate(entityClass, tenant);
        if (scope == null || maxRows < 1 || maxRows > MAX_ROWS) throw fail(Code.INVALID_REQUEST);
        if (scope != Scope.REDIS_RESIDENT) throw fail(Code.UNSUPPORTED_SOURCE);
        if (!admission.tryAcquire()) throw fail(Code.BUSY);
        try (var ticket = fence.begin(barrier.key(tenant))) {
            String witness = topology.captureTopology(), token = UUID.randomUUID().toString();
            try (Context context = new Context(ticket, witness, token)) {
                boolean acquired = false;
                try {
                    State state = state(execute(context, ACQUIRE, List.of(), List.of()));
                    acquired = true;
                    int examined = 0, frames = 0;
                    long started = System.nanoTime();
                    while (!state.done && examined < maxRows && System.nanoTime() - started < MAX_NANOS) {
                        if (state.pending.isEmpty()) {
                            if (++frames > MAX_FRAMES) break;
                            var page = scan(context, state.cursor);
                            execute(context, STAGE, List.of(), List.of(state.cursor,
                                    Base64.getEncoder().encodeToString(page.encoded())));
                            state = state(execute(context, OWNED + "renew(); return snapshot()", List.of(), List.of()));
                        }
                        Frame frame = frame(state.pending);
                        if (state.offset > frame.keys.size()) throw fail(Code.CURSOR_MISMATCH);
                        if (frame.keys.isEmpty()) {
                            execute(context, EMPTY, List.of(), List.of(frame.next, frame.next.equals("0") ? "1" : "0"));
                        } else {
                            if (state.offset == frame.keys.size()) throw fail(Code.CURSOR_MISMATCH);
                            process(context, tenant, state, frame);
                            examined++;
                            afterCommit.run();
                        }
                        state = state(execute(context, OWNED + "return snapshot()", List.of(), List.of()));
                    }
                    Status status = !state.done ? Status.MORE
                            : state.conflicts + state.blocked > 0 ? Status.COMPLETE_WITH_GAPS : Status.COMPLETE;
                    return ticket.complete(new Step(status, examined, state.total, state.updated,
                            state.conflicts, state.foreign, state.missing, state.blocked));
                } finally {
                    if (acquired) {
                        try { execute(context, RELEASE, List.of(), List.of()); }
                        catch (RuntimeException ignored) { /* Finite Redis lease remains the authority. */ }
                    }
                }
            }
        } catch (Failure safe) { throw fail(safe.code()); }
        catch (BoundedRedisWire.Failure limited) {
            throw fail(limited.code() == BoundedRedisWire.Code.BUSY ? Code.BUSY
                    : limited.code() == BoundedRedisWire.Code.INPUT_LIMIT ? Code.INPUT_LIMIT : Code.STORAGE_UNAVAILABLE);
        }
        catch (ProtectionException rejected) {
            throw fail(rejected.code() == ProtectionException.Code.BUSY ? Code.BUSY : Code.GENERATION_REJECTED);
        }
        catch (RuntimeException unsafe) { throw fail(Code.STORAGE_UNAVAILABLE); }
        finally { admission.release(); }
    }

    /** Explicit new traversal, never silently relabel an old cursor to a new generation/target. */
    public void startNewPass(Class<?> entityClass, String tenant) {
        validate(entityClass, tenant);
        if (!admission.tryAcquire()) throw fail(Code.BUSY);
        try (var ticket = fence.begin(barrier.key(tenant))) {
            try (Context context = new Context(ticket, topology.captureTopology(), UUID.randomUUID().toString())) {
                execute(context, RESET, List.of(), List.of());
                ticket.complete(null);
            }
        } catch (Failure safe) { throw fail(safe.code()); }
        catch (BoundedRedisWire.Failure limited) {
            throw fail(limited.code() == BoundedRedisWire.Code.BUSY ? Code.BUSY
                    : limited.code() == BoundedRedisWire.Code.INPUT_LIMIT ? Code.INPUT_LIMIT : Code.STORAGE_UNAVAILABLE);
        }
        catch (ProtectionException rejected) {
            throw fail(rejected.code() == ProtectionException.Code.BUSY ? Code.BUSY : Code.GENERATION_REJECTED);
        }
        catch (RuntimeException unsafe) { throw fail(Code.STORAGE_UNAVAILABLE); }
        finally { admission.release(); }
    }

    /**
     * Exact optional-store hook for the purge coordinator, AFTER its approved global closure.
     * Local DRAINING is never a remote runtime acknowledgement. No SQL and no wildcard deletion.
     * A caller needing durable purge counts must journal this store with its canonical participant.
     */
    public Cleanup discardCursorAfterClose(Lease lease, long closedGeneration) {
        if (!CacheTenantContext.isAdministrative()) throw fail(Code.INVALID_REQUEST);
        try {
            if (!fence.closeAndDrain(lease, closedGeneration).drained()) return Cleanup.DRAINING;
            var names = new ArrayList<>(barrier.fencedKeys(lease));
            names.add(cursorKey(lease.key()));
            redis.requireSameSlot(names);
            Object result = redis.eval(RedisGenerationBarrier.closedCheckLua() + """
                local kind=redis.call('TYPE',KEYS[3]).ok
                if kind=='none' then return 1 end
                if kind~='hash' or redis.call('HLEN',KEYS[3])~=16
                  or redis.call('HGET',KEYS[3],'fmt')~='1' then return -1 end
                local expected={fmt=true,target=true,gen=true,topo=true,cursor=true,pending=true,
                  offset=true,done=true,total=true,updated=true,conflicts=true,foreign=true,
                  missing=true,blocked=true,token=true,['until']=true}
                for _,f in ipairs(redis.call('HKEYS',KEYS[3])) do
                  if not expected[f] or redis.call('HSTRLEN',KEYS[3],f)>(f=='pending' and 90000 or 128)
                    then return -1 end
                end
                local g=redis.call('HGET',KEYS[3],'gen')
                if not g or not string.match(g,'^[1-9][0-9]*$') or #g>19
                  or #g>#ARGV[1] or (#g==#ARGV[1] and g>ARGV[1]) then return -1 end
                if not redis.acl_check_cmd or not redis.acl_check_cmd('DEL',KEYS[3]) then return -1 end
                redis.call('DEL',KEYS[3]); return 1
                """, names, barrier.fencedArgs(lease, closedGeneration));
            return result instanceof Number n && n.longValue() == 1 ? Cleanup.CLEARED : Cleanup.INCOMPLETE;
        } catch (RuntimeException unsafe) { throw fail(Code.STORAGE_UNAVAILABLE); }
    }

    private void validate(Class<?> entityClass, String tenant) {
        if (entityClass != metadata.entityType() || tenant == null || tenant.isBlank()
                || tenant.length() > 256 || Utf8Length.of(tenant) > 512
                || !CacheTenantContext.isAdministrative()
                    && !CacheTenantContext.currentTenant().filter(tenant::equals).isPresent())
            throw fail(Code.INVALID_REQUEST);
        try { EncryptedCodecSupport.tenantNamespace(metadata.redisNamespace(), tenant); }
        catch (RuntimeException malformed) { throw fail(Code.INVALID_REQUEST); }
    }

    /** Stable location only; no raw tenant, ID, field value, or current key ID in the key name. */
    public String cursorKey(ProtectionKey key) { return barrier.generationKey(key) + ":reencryption"; }

    private final class Context implements AutoCloseable {
        final RedisTenantReadFence.Ticket ticket;
        final String topology, token;
        BoundedRedisWire.Session session;
        Context(RedisTenantReadFence.Ticket ticket, String topology, String token) {
            this.ticket = ticket; this.topology = topology; this.token = token;
        }
        BoundedRedisWire.Reply call(BoundedRedisWire.Limits limits, String... command) {
            if (session == null) session = wire.connect();
            try { return session.call(limits, command); }
            catch (BoundedRedisWire.Failure failed) {
                close(); // A bounded parser rejection may leave an unread RESP tail.
                throw failed;
            }
        }
        public void close() { if (session != null) { session.close(); session = null; } }
    }
    private record Frame(String next, List<String> keys) {}

    private BoundedRedisWire.Reply scan(Context c, String cursor) {
        int count = 128;
        while (true) {
            c.ticket.check();
            try {
                var reply = c.call(SCAN_LIMIT, "SCAN", cursor, "MATCH",
                        glob(keys.entityKeyPrefix(metadata.redisNamespace())) + "*", "COUNT", Integer.toString(count));
                // The session is pinned to one primary. Staging also rechecks original run-ID/generation.
                c.ticket.check();
                return reply;
            } catch (BoundedRedisWire.Failure limited) {
                if (limited.code() != BoundedRedisWire.Code.INPUT_LIMIT) throw limited;
                if (count == 1) throw fail(Code.INPUT_LIMIT);
                count = Math.max(1, count / 2); // Same cursor: never truncate a SCAN response.
            }
        }
    }

    private void process(Context c, String tenant, State state, Frame frame) {
        String rawKey = frame.keys.get(state.offset);
        var parsed = keys.parseEntityKey(rawKey, prefixes);
        String id = parsed.map(RedisKeyStrategy.EntityKey::id).orElse("");
        boolean canonical = parsed.isPresent() && !id.isEmpty() && id.length() <= 1024
                && rawKey.equals(keys.entityKey(metadata.redisNamespace(), id));
        var rowKeys = canonical ? List.of(rawKey, keys.versionKey(metadata.redisNamespace(), id),
                keys.versionKey(metadata.redisNamespace(), id) + ":tenant",
                keys.tombstoneKey(metadata.redisNamespace(), id), keys.tenantHotOwnerKey(metadata.redisNamespace(), id))
                : List.<String>of();
        String outcome = "blocked", before = "", replacement = "", version = "", hot = "";
        if (canonical) {
            var read = list(execute(c, READ, rowKeys, List.of(tenant)));
            String result = text(read.get(0));
            if (Set.of("foreign", "missing", "blocked").contains(result)) outcome = result;
            else if ("owned".equals(result) && read.size() == 4) {
                before = text(read.get(1)); version = text(read.get(2)); hot = text(read.get(3));
                try {
                    positive(version);
                    T entity = c.ticket.compute(() -> codec.fromRedisValue(text(read.get(1))));
                    if (!tenant.equals(metadata.extractTenantId(entity))
                            || !id.equals(String.valueOf(metadata.idAccessor().apply(entity)))) throw fail(Code.UNBOUND_MAPPING);
                    replacement = c.ticket.compute(() -> codec.toRedisValue(entity));
                    long oldBytes = Utf8Length.of(before), newBytes = Utf8Length.of(replacement);
                    if (newBytes > MAX_PAYLOAD || replacement.isEmpty()) throw fail(Code.INPUT_LIMIT);
                    T roundTrip = codec.fromRedisValue(replacement);
                    if (!same(codec.toColumns(entity), codec.toColumns(roundTrip))
                            || !same(codec.toIndexColumns(entity), codec.toIndexColumns(roundTrip)))
                        throw fail(Code.UNBOUND_MAPPING);
                    if (!hot.isEmpty()) {
                        String set = keys.tenantHotSetKey(metadata.redisNamespace(), metadata.tenantFieldName(), tenant);
                        String bytes = keys.tenantHotPayloadBytesKey(metadata.redisNamespace(), metadata.tenantFieldName(), tenant);
                        RedisPageCacheManager.purgeTenantPayloadBytes(hot, set, bytes);
                        // Never grow an existing quota reservation without the canonical admission plan.
                        if (newBytes > oldBytes) throw fail(Code.INPUT_LIMIT);
                    }
                    outcome = "updated";
                } catch (RuntimeException unsafe) { outcome = "blocked"; replacement = ""; }
            }
        }
        var args = new ArrayList<>(List.of(Integer.toString(state.offset), Integer.toString(frame.keys.size()),
                frame.next, frame.next.equals("0") ? "1" : "0", outcome, tenant, version, before, replacement, hot));
        execute(c, COMMIT, rowKeys, args);
    }

    private Object execute(Context c, String body, List<String> extraKeys, List<String> extraArgs) {
        Generation original = c.ticket.generation();
        String allocator = keys.namespacePrefix(metadata.redisNamespace()) + ":security:version:" + original.key().tenantToken();
        var allKeys = new ArrayList<>(List.of(barrier.generationKey(original.key()), allocator,
                allocator + ":next", cursorKey(original.key())));
        allKeys.addAll(extraKeys);
        var args = new ArrayList<>(List.of(Long.toString(original.value()), c.token, target, c.topology,
                LEASE_MILLIS, CURSOR_SECONDS));
        args.addAll(extraArgs);
        String program = ORIGINAL + body;
        RedisProtectionArguments.validate(program, allKeys, args);
        redis.requireSameSlot(allKeys);
        var command = new ArrayList<>(List.of("EVAL", program, Integer.toString(allKeys.size())));
        command.addAll(allKeys); command.addAll(args);
        // Ticket stays physically enrolled throughout the bounded socket operation.
        try {
            Object value = c.call(IO_LIMIT, command.toArray(String[]::new)).value();
            if (value instanceof List<?> error && error.size() == 2 && "~R1".equals(error.get(0)))
                throw fail(Code.valueOf(text(error.get(1))));
            c.ticket.generation();
            return value;
        } catch (BoundedRedisWire.Failure failure) {
            throw fail(failure.code() == BoundedRedisWire.Code.INPUT_LIMIT ? Code.INPUT_LIMIT : Code.STORAGE_UNAVAILABLE);
        }
    }

    private static boolean same(Map<String, Object> a, Map<String, Object> b) {
        if (a == null || b == null || !a.keySet().equals(b.keySet()) || a.size() > 130) return false;
        for (String key : a.keySet()) if (!Objects.deepEquals(a.get(key), b.get(key))) return false;
        return true;
    }

    private record State(String cursor, String pending, int offset, boolean done,
            long total, long updated, long conflicts, long foreign, long missing, long blocked) {}

    private static State state(Object value) {
        var v = list(value);
        if (v.size() != 10) throw fail(Code.CURSOR_MISMATCH);
        String cursor = text(v.get(0)), pending = text(v.get(1)), done = text(v.get(3));
        if (!cursor.matches("[0-9]{1,20}") || pending.length() > 90000 || !Set.of("0", "1").contains(done))
            throw fail(Code.CURSOR_MISMATCH);
        long offset = number(v.get(2));
        if (offset > 256) throw fail(Code.CURSOR_MISMATCH);
        return new State(cursor, pending, (int) offset, done.equals("1"), number(v.get(4)),
                number(v.get(5)), number(v.get(6)), number(v.get(7)), number(v.get(8)), number(v.get(9)));
    }

    private static Frame frame(String pending) {
        try {
            var v = list(BoundedRedisWire.decode(Base64.getDecoder().decode(pending), SCAN_LIMIT).value());
            if (v.size() != 2 || !text(v.get(0)).matches("[0-9]{1,20}")) throw fail(Code.CURSOR_MISMATCH);
            var entries = list(v.get(1));
            if (entries.size() > 256) throw fail(Code.INPUT_LIMIT);
            var keys = new ArrayList<String>(entries.size());
            for (Object entry : entries) keys.add(text(entry));
            return new Frame(text(v.get(0)), List.copyOf(keys));
        } catch (RuntimeException unsafe) { throw fail(Code.CURSOR_MISMATCH); }
    }

    private static CacheKeyProvider pin(CacheKeyProvider provider, EntityMetadata<?, ?> metadata) {
        if (provider == null) throw fail(Code.UNBOUND_MAPPING);
        var currents = new HashMap<String, KeyMaterial>();
        for (var descriptor : metadata.encryptedColumns().values()) {
            KeyMaterial key = provider.current(descriptor.keyRing());
            if (key == null) throw fail(Code.UNBOUND_MAPPING);
            currents.put(descriptor.keyRing(), key);
        }
        Map<String, KeyMaterial> pinned = Map.copyOf(currents);
        return new CacheKeyProvider() {
            public KeyMaterial current(String ring) { return pinned.get(ring); }
            public Optional<KeyMaterial> byId(String ring, String id) {
                KeyMaterial key = pinned.get(ring);
                return key != null && key.keyId().equals(id) ? Optional.of(key) : provider.byId(ring, id);
            }
        };
    }

    private static String target(EntityMetadata<?, ?> m, CacheKeyProvider p) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of("cachedb-reencrypt-resident-v1", m.entityType().getName(),
                    m.redisNamespace(), m.tableName(), m.idColumn(), m.tenantFieldName(),
                    Integer.toString(m.schemaVersion()), Integer.toString(m.compressionThresholdBytes())))
                add(digest, part);
            for (String column : m.columns()) add(digest, column);
            for (String column : new TreeSet<>(m.encryptedColumns().keySet())) {
                var d = m.encryptedColumns().get(column);
                for (String part : List.of(column, d.keyRing(), d.scope().name(),
                        Boolean.toString(d.blindIndex()), p.current(d.keyRing()).keyId())) add(digest, part);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception unsafe) { throw fail(Code.UNBOUND_MAPPING); }
    }
    private static void add(MessageDigest d, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        d.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII)); d.update(bytes);
    }
    private static String glob(String prefix) {
        return prefix.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")
                .replace("[", "\\[").replace("]", "\\]");
    }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> values)) throw fail(Code.CURSOR_MISMATCH);
        return values;
    }
    private static String text(Object value) {
        if (!(value instanceof String text)) throw fail(Code.CURSOR_MISMATCH);
        return text;
    }
    private static long number(Object value) {
        String s = text(value);
        if (!s.matches("0|[1-9][0-9]{0,11}")) throw fail(Code.CURSOR_MISMATCH);
        return Long.parseLong(s);
    }
    private static long positive(String text) {
        if (!text.matches("[1-9][0-9]{0,18}")) throw fail(Code.UNBOUND_MAPPING);
        long n = Long.parseLong(text);
        if (n <= 0) throw fail(Code.UNBOUND_MAPPING);
        return n;
    }
    private static Failure fail(Code code) { return new Failure(code); }
}

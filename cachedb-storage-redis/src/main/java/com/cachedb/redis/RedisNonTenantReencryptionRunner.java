package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.codec.Utf8Length;
import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.core.model.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Non-tenant Redis-resident rotation. No tenant generation, SQL, producer/save API or private
 * read fence is fabricated. Runtime metadata, framed codec and transport are explicit bindings.
 */
public final class RedisNonTenantReencryptionRunner<T, ID> {
    public enum Scope { REDIS_RESIDENT, SOURCE }
    public enum Status { MORE, COMPLETE, COMPLETE_WITH_GAPS }
    public enum Code {
        INVALID_REQUEST, UNBOUND_MAPPING, SOURCE_REQUIRES_FENCED_MAINTENANCE, BUSY,
        CURSOR_EXPIRED, CURSOR_MISMATCH, INPUT_LIMIT, TOPOLOGY_CHANGED, STORAGE_UNAVAILABLE
    }
    public static final class Failure extends RuntimeException {
        private final Code code;
        Failure(Code code) { super(code.name(), null, false, false); this.code = code; }
        public Code code() { return code; }
    }
    /** quotaFree is an explicit immutable route-inventory contract, never ambient context. */
    public record QuotaPolicy(boolean quotaFree, Set<String> quotaColumns) {
        public QuotaPolicy {
            if (quotaColumns == null || quotaColumns.size() > 16 || quotaFree && !quotaColumns.isEmpty())
                throw fail(Code.INVALID_REQUEST);
            quotaColumns = Set.copyOf(quotaColumns);
        }
        public static QuotaPolicy withoutRouteQuotas() { return new QuotaPolicy(true, Set.of()); }
        public static QuotaPolicy nonGrowing(Set<String> columns) { return new QuotaPolicy(false, columns); }
    }
    /** Opaque passId is not an entity/tenant ID. SCAN counts occurrences, not unique rows. */
    public record Step(UUID passId, Status status, int examinedThisCall, long examined,
            long rewritten, long conflicts, long missing, long blocked, long growthDeferred) {}

    private static final int MAX_ROWS = 10000, MAX_PAYLOAD = 262144, MAX_FRAMES = 32;
    private static final long MAX_NANOS = 5_000_000_000L;
    private static final BoundedRedisWire.Limits SCAN =
            new BoundedRedisWire.Limits(65536, 8192, 259, 16384);
    private static final BoundedRedisWire.Limits IO = BoundedRedisWire.Limits.entries();
    private static final String HEADER = """
        local run=string.match(redis.call('INFO','SERVER'),'run_id:([0-9a-f]+)')
        if not run or run~=ARGV[1] then return {'~N1','TOPOLOGY_CHANGED'} end
        local function kind(k,t)
          local actual=redis.call('TYPE',k).ok
          return actual=='none' or actual==t
        end
        if not kind(KEYS[1],'hash') or redis.call('HLEN',KEYS[1])>18
          then return {'~N1','CURSOR_MISMATCH'} end
        local fields={'fmt','target','scope','run','topo','pass','cursor','pending','offset','done',
          'total','updated','conflicts','missing','blocked','growth','token','until'}
        local allowed={}
        for _,f in ipairs(fields) do allowed[f]=true end
        for _,f in ipairs(redis.call('HKEYS',KEYS[1])) do
          if not allowed[f] then return {'~N1','CURSOR_MISMATCH'} end
        end
        for _,f in ipairs(fields) do
          if redis.call('HSTRLEN',KEYS[1],f)>(f=='pending' and 90000 or 128)
            then return {'~N1','INPUT_LIMIT'} end
        end
        local tm=redis.call('TIME')
        local now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000)
        local function format()
          return redis.call('HLEN',KEYS[1])==18 and redis.call('HGET',KEYS[1],'fmt')=='N1'
            and redis.call('HGET',KEYS[1],'scope')=='REDIS_RESIDENT'
        end
        local function stamp()
          return format() and redis.call('HGET',KEYS[1],'target')==ARGV[2]
            and redis.call('HGET',KEYS[1],'run')==ARGV[1]
            and redis.call('HGET',KEYS[1],'topo')==ARGV[3]
        end
        local function owned()
          return stamp() and redis.call('HGET',KEYS[1],'token')==ARGV[4]
            and tonumber(redis.call('HGET',KEYS[1],'until') or '0')>now
        end
        local function renew()
          redis.call('HSET',KEYS[1],'until',tostring(now+tonumber(ARGV[5])))
          redis.call('EXPIRE',KEYS[1],ARGV[6])
        end
        local function snapshot()
          return redis.call('HMGET',KEYS[1],'pass','cursor','pending','offset','done',
            'total','updated','conflicts','missing','blocked','growth')
        end
        """;
    private static final String ACQUIRE = """
        local exists=redis.call('EXISTS',KEYS[1])==1
        if not exists and ARGV[8]~='' then return {'~N1','CURSOR_EXPIRED'} end
        if exists then
          if not stamp() then return {'~N1','CURSOR_MISMATCH'} end
          if ARGV[8]~='' and redis.call('HGET',KEYS[1],'pass')~=ARGV[8]
            then return {'~N1','CURSOR_MISMATCH'} end
          if tonumber(redis.call('HGET',KEYS[1],'until') or '0')>now
            then return {'~N1','BUSY'} end
        else
          redis.call('HSET',KEYS[1],'fmt','N1','target',ARGV[2],'scope','REDIS_RESIDENT',
            'run',ARGV[1],'topo',ARGV[3],'pass',ARGV[7],'cursor','0','pending','','offset','0',
            'done','0','total','0','updated','0','conflicts','0','missing','0','blocked','0',
            'growth','0','token','','until','0')
        end
        redis.call('HSET',KEYS[1],'token',ARGV[4]); renew(); return snapshot()
        """;
    private static final String OWNED = """
        if not owned() then return {'~N1','BUSY'} end
        """;
    private static final String STAGE = OWNED + """
        if redis.call('HGET',KEYS[1],'pending')~='' or redis.call('HGET',KEYS[1],'cursor')~=ARGV[9]
          or redis.call('HGET',KEYS[1],'done')~='0' then return {'~N1','CURSOR_MISMATCH'} end
        redis.call('HSET',KEYS[1],'pending',ARGV[10],'offset','0'); renew(); return 1
        """;
    private static final String READ = OWNED + """
        for i=2,6 do if not kind(KEYS[i],'string') then return {'blocked'} end end
        renew()
        if redis.call('EXISTS',KEYS[5])==1 then return {'blocked'} end
        if redis.call('EXISTS',KEYS[4])==1 or redis.call('EXISTS',KEYS[2])==0 then return {'missing'} end
        if redis.call('STRLEN',KEYS[2])>262144 or redis.call('STRLEN',KEYS[3])>19
          or redis.call('STRLEN',KEYS[6])>16384 then return {'blocked'} end
        local version=redis.call('GET',KEYS[3])
        if not version or not string.match(version,'^[1-9][0-9]*$') then return {'blocked'} end
        return {'owned',redis.call('GET',KEYS[2]),version,redis.call('GET',KEYS[6]) or ''}
        """;
    private static final String COMMIT = OWNED + """
        if redis.call('HGET',KEYS[1],'offset')~=ARGV[9] or redis.call('HGET',KEYS[1],'pending')==''
          then return {'~N1','CURSOR_MISMATCH'} end
        local counters={'total','updated','conflicts','missing','blocked','growth'}
        for _,f in ipairs(counters) do
          local n=redis.call('HGET',KEYS[1],f)
          if not n or not string.match(n,'^%d+$') or #n>12 or tonumber(n)>=999999999999
            then return {'~N1','INPUT_LIMIT'} end
        end
        local outcome=ARGV[13]
        if outcome=='updated' then
          for i=2,6 do if not kind(KEYS[i],'string') then outcome='blocked' end end
          if outcome=='updated' then
            if redis.call('STRLEN',KEYS[2])>262144 or redis.call('STRLEN',KEYS[3])>19
              or redis.call('STRLEN',KEYS[6])>16384 then outcome='blocked'
            elseif redis.call('EXISTS',KEYS[5])==1 or redis.call('EXISTS',KEYS[4])==1
              or redis.call('GET',KEYS[3])~=ARGV[14] or redis.call('GET',KEYS[2])~=ARGV[15]
              or (redis.call('GET',KEYS[6]) or '')~=ARGV[17] then outcome='conflicts' end
          end
        end
        -- Lua errors do not roll back writes. Validate ACL and numeric/schema failures BEFORE SET.
        if not redis.acl_check_cmd then return {'~N1','STORAGE_UNAVAILABLE'} end
        for _,f in ipairs(counters) do
          if not redis.acl_check_cmd('HINCRBY',KEYS[1],f,1)
            then return {'~N1','STORAGE_UNAVAILABLE'} end
        end
        if not redis.acl_check_cmd('HINCRBY',KEYS[1],'offset',1)
          or not redis.acl_check_cmd('HSET',KEYS[1],'pending','','offset','0','cursor',ARGV[11],
            'done',ARGV[12],'until',tostring(now+tonumber(ARGV[5])))
          or not redis.acl_check_cmd('EXPIRE',KEYS[1],ARGV[6])
          or (outcome=='updated' and not redis.acl_check_cmd('SET',KEYS[2],ARGV[16],'XX','KEEPTTL'))
          then return {'~N1','STORAGE_UNAVAILABLE'} end
        if outcome=='updated' then
          if not redis.call('SET',KEYS[2],ARGV[16],'XX','KEEPTTL') then outcome='conflicts' end
        end
        redis.call('HINCRBY',KEYS[1],'total',1); redis.call('HINCRBY',KEYS[1],outcome,1)
        if tonumber(ARGV[9])+1==tonumber(ARGV[10]) then
          redis.call('HSET',KEYS[1],'pending','','offset','0','cursor',ARGV[11],'done',ARGV[12])
        else redis.call('HINCRBY',KEYS[1],'offset',1) end
        renew(); return outcome
        """;
    private static final String EMPTY = OWNED + """
        if redis.call('HGET',KEYS[1],'pending')=='' then return {'~N1','CURSOR_MISMATCH'} end
        redis.call('HSET',KEYS[1],'pending','','offset','0','cursor',ARGV[9],'done',ARGV[10])
        renew(); return 1
        """;
    private static final String RELEASE = OWNED + """
        redis.call('HSET',KEYS[1],'token','','until','0')
        redis.call('EXPIRE',KEYS[1],ARGV[6]); return 1
        """;
    private static final String RESET = """
        if redis.call('EXISTS',KEYS[1])==1 then
          if not format() then return {'~N1','CURSOR_MISMATCH'} end
          if tonumber(redis.call('HGET',KEYS[1],'until') or '0')>now then return {'~N1','BUSY'} end
        end
        redis.call('DEL',KEYS[1]); return 1
        """;

    private final EntityMetadata<T, ID> metadata;
    private final EntityCodec<T> codec;
    private final RedisClientOperations redis;
    private final RedisKeyStrategy keys;
    private final RedisReencryptionTransport wire;
    private final QuotaPolicy quotas;
    private final String target;
    private final Map<String, String> prefixes;
    private final Semaphore admission = new Semaphore(1);
    private final Runnable afterCommit;

    /** Safe default: do not grow payloads; unbound quota-owner schemas are blocked. */
    public RedisNonTenantReencryptionRunner(EntityMetadata<T, ID> metadata, EntityCodec<T> runtimeCodec,
            CacheKeyProvider provider, RedisClientOperations redis, RedisKeyStrategy keys, BoundedRedisWire wire) {
        this(metadata, runtimeCodec, provider, redis, keys, wire, QuotaPolicy.nonGrowing(Set.of()));
    }
    public RedisNonTenantReencryptionRunner(EntityMetadata<T, ID> metadata, EntityCodec<T> runtimeCodec,
            CacheKeyProvider provider, RedisClientOperations redis, RedisKeyStrategy keys, BoundedRedisWire wire,
            QuotaPolicy quotas) {
        this(metadata, runtimeCodec, provider, redis, keys, wire, quotas, () -> {});
    }
    RedisNonTenantReencryptionRunner(EntityMetadata<T, ID> metadata, EntityCodec<T> runtimeCodec,
            CacheKeyProvider provider, RedisClientOperations redis, RedisKeyStrategy keys, BoundedRedisWire wire,
            QuotaPolicy quotas, Runnable afterCommit) {
        this(metadata, runtimeCodec, provider, redis, keys, RedisReencryptionTransport.borrowed(wire), quotas, afterCommit);
    }

    public RedisNonTenantReencryptionRunner(EntityMetadata<T, ID> metadata, EntityCodec<T> runtimeCodec,
            CacheKeyProvider provider, RedisClientOperations redis, RedisKeyStrategy keys,
            RedisReencryptionTransport wire, QuotaPolicy quotas) {
        this(metadata, runtimeCodec, provider, redis, keys, wire, quotas, () -> {});
    }

    private RedisNonTenantReencryptionRunner(EntityMetadata<T, ID> metadata, EntityCodec<T> runtimeCodec,
            CacheKeyProvider provider, RedisClientOperations redis, RedisKeyStrategy keys,
            RedisReencryptionTransport wire, QuotaPolicy quotas, Runnable afterCommit) {
        try {
            this.metadata = Objects.requireNonNull(metadata);
            if (metadata.tenantFieldName() != null || metadata.tenantPurgeEnabled()
                    || metadata.encryptedColumns().isEmpty()) throw fail(Code.UNBOUND_MAPPING);
            EncryptedStorageChecks.validateMetadata(metadata);
            CacheKeyProvider pinned = pin(provider, metadata);
            this.codec = Objects.requireNonNull(runtimeCodec).bindEncryption(pinned);
            EncryptedStorageChecks.requireBound(metadata, codec);
            if (!codec.encryptedMapping()) throw fail(Code.UNBOUND_MAPPING);
            this.redis = Objects.requireNonNull(redis); this.keys = Objects.requireNonNull(keys);
            this.wire = Objects.requireNonNull(wire); this.quotas = Objects.requireNonNull(quotas);
            for (String column : quotas.quotaColumns())
                if (!metadata.columns().contains(column) || metadata.encryptedColumns().containsKey(column))
                    throw fail(Code.UNBOUND_MAPPING);
            this.prefixes = keys.entityKeyPrefixes(List.of(metadata.redisNamespace()));
            this.target = target(metadata, pinned, quotas);
            this.afterCommit = Objects.requireNonNull(afterCommit);
            if (redis.clustered()) {
                String tag = tag(cursorKey());
                for (String id : List.of("layout-a", "layout-b"))
                    for (String key : rowKeys(id))
                        if (!tag.equals(tag(key))) throw fail(Code.UNBOUND_MAPPING);
                redis.requireSameSlot(List.of(cursorKey(), keys.entityKey(metadata.redisNamespace(), "layout-a")));
            }
        } catch (RuntimeException unsafe) { throw fail(Code.UNBOUND_MAPPING); }
    }

    public Step reencrypt(Class<?> entityClass, int maxRows) {
        return run(entityClass, maxRows, Scope.REDIS_RESIDENT, null);
    }
    public Step reencrypt(Class<?> entityClass, int maxRows, Scope scope) {
        return run(entityClass, maxRows, scope, null);
    }
    /** Unlike a new invocation, resume NEVER creates a missing/expired pass. */
    public Step resume(Class<?> entityClass, UUID expectedPass, int maxRows) {
        if (expectedPass == null) throw fail(Code.INVALID_REQUEST);
        return run(entityClass, maxRows, Scope.REDIS_RESIDENT, expectedPass);
    }
    /** Exact store name; no entity ID, field value or invented tenant token. */
    public String cursorKey() { return keys.namespacePrefix(metadata.redisNamespace()) + ":reencryption:resident:n1"; }

    public void startNewPass(Class<?> entityClass) {
        validate(entityClass, 1);
        if (!admission.tryAcquire()) throw fail(Code.BUSY);
        try (var context = new Context(witness(), null)) {
            execute(context, RESET, List.of(), List.of());
        } catch (Failure safe) { throw fail(safe.code()); }
        catch (RuntimeException unsafe) { throw fail(Code.STORAGE_UNAVAILABLE); }
        finally { admission.release(); }
    }

    private Step run(Class<?> entityClass, int maxRows, Scope scope, UUID expectedPass) {
        validate(entityClass, maxRows);
        if (scope == null) throw fail(Code.INVALID_REQUEST);
        if (scope == Scope.SOURCE) throw fail(Code.SOURCE_REQUIRES_FENCED_MAINTENANCE);
        if (!admission.tryAcquire()) throw fail(Code.BUSY);
        try (var context = new Context(witness(), expectedPass)) {
            boolean acquired = false;
            try {
                State state = state(execute(context, ACQUIRE, List.of(), List.of()));
                acquired = true;
                int examined = 0, frames = 0;
                long started = System.nanoTime();
                while (!state.done && examined < maxRows && System.nanoTime() - started < MAX_NANOS) {
                    if (state.pending.isEmpty()) {
                        if (++frames > MAX_FRAMES) break;
                        var reply = scan(context, state.cursor);
                        execute(context, STAGE, List.of(), List.of(state.cursor,
                                Base64.getEncoder().encodeToString(reply.encoded())));
                        state = state(execute(context, OWNED + "return snapshot()", List.of(), List.of()));
                    }
                    Frame frame = frame(state.pending);
                    if (state.offset > frame.keys.size()) throw fail(Code.CURSOR_MISMATCH);
                    if (frame.keys.isEmpty()) {
                        execute(context, EMPTY, List.of(), List.of(frame.next, frame.next.equals("0") ? "1" : "0"));
                    } else {
                        if (state.offset == frame.keys.size()) throw fail(Code.CURSOR_MISMATCH);
                        process(context, state, frame);
                        examined++; afterCommit.run();
                    }
                    state = state(execute(context, OWNED + "return snapshot()", List.of(), List.of()));
                }
                Status status = !state.done ? Status.MORE
                        : state.conflicts + state.blocked + state.growth > 0 ? Status.COMPLETE_WITH_GAPS : Status.COMPLETE;
                return new Step(state.pass, status, examined, state.total, state.updated,
                        state.conflicts, state.missing, state.blocked, state.growth);
            } finally {
                if (acquired) try { execute(context, RELEASE, List.of(), List.of()); }
                catch (RuntimeException ignored) { /* Redis's finite lease excludes a late worker. */ }
            }
        } catch (Failure safe) { throw fail(safe.code()); }
        catch (BoundedRedisWire.Failure transport) { throw wireFailure(transport); }
        catch (RuntimeException unsafe) { throw fail(Code.STORAGE_UNAVAILABLE); }
        finally { admission.release(); }
    }

    private void validate(Class<?> entityClass, int maxRows) {
        if (entityClass != metadata.entityType() || !CacheTenantContext.isAdministrative()
                || maxRows < 1 || maxRows > MAX_ROWS) throw fail(Code.INVALID_REQUEST);
    }
    private List<String> rowKeys(String id) {
        String ns = metadata.redisNamespace();
        return List.of(keys.entityKey(ns, id), keys.versionKey(ns, id), keys.tombstoneKey(ns, id),
                keys.versionKey(ns, id) + ":tenant", keys.tenantHotOwnerKey(ns, id));
    }
    private void process(Context c, State state, Frame frame) {
        String rawKey = frame.keys.get(state.offset);
        var parsed = keys.parseEntityKey(rawKey, prefixes);
        String id = parsed.map(RedisKeyStrategy.EntityKey::id).orElse("");
        boolean canonical = parsed.isPresent() && !id.isEmpty() && id.length() <= 1024
                && rawKey.equals(keys.entityKey(metadata.redisNamespace(), id));
        List<String> names = canonical ? rowKeys(id) : List.of();
        String outcome = "blocked", before = "", after = "", version = "", hot = "";
        if (canonical) {
            var read = list(execute(c, READ, names, List.of()));
            if (read.size() == 1 && Set.of("missing", "blocked").contains(text(read.get(0)))) outcome = text(read.get(0));
            else if (read.size() == 4 && "owned".equals(read.get(0))) {
                before = text(read.get(1)); version = text(read.get(2)); hot = text(read.get(3));
                try {
                    positive(version);
                    T entity = codec.fromRedisValue(before);
                    if (!id.equals(String.valueOf(metadata.idAccessor().apply(entity)))
                            || metadata.extractTenantId(entity) != null) throw fail(Code.UNBOUND_MAPPING);
                    after = codec.toRedisValue(entity);
                    long oldBytes = Utf8Length.of(before), newBytes = Utf8Length.of(after);
                    if (newBytes > MAX_PAYLOAD || after.isEmpty()) throw fail(Code.INPUT_LIMIT);
                    T decoded = codec.fromRedisValue(after);
                    Map<String, Object> logical = codec.toColumns(entity);
                    if (!same(logical, codec.toColumns(decoded))
                            || !same(codec.toIndexColumns(entity), codec.toIndexColumns(decoded)))
                        throw fail(Code.UNBOUND_MAPPING);
                    boolean accounted = hot.isEmpty();
                    if (!hot.isEmpty() && !quotas.quotaFree()) {
                        for (String column : quotas.quotaColumns()) {
                            Object owner = codec.columnValue(logical, column);
                            if (owner == null) continue;
                            String value = String.valueOf(owner);
                            if (value.length() > 512 || Utf8Length.of(value) > 1024) continue;
                            try {
                                RedisPageCacheManager.purgeTenantPayloadBytes(hot,
                                        keys.tenantHotSetKey(metadata.redisNamespace(), column, value),
                                        keys.tenantHotPayloadBytesKey(metadata.redisNamespace(), column, value));
                                accounted = true; break;
                            } catch (RuntimeException mismatched) { /* Try only the bounded declared routes. */ }
                        }
                    }
                    if (!accounted) throw fail(Code.UNBOUND_MAPPING);
                    outcome = !quotas.quotaFree() && newBytes > oldBytes ? "growth" : "updated";
                } catch (RuntimeException unsafe) { outcome = "blocked"; after = ""; }
            }
        }
        execute(c, COMMIT, names, List.of(Integer.toString(state.offset), Integer.toString(frame.keys.size()),
                frame.next, frame.next.equals("0") ? "1" : "0", outcome, version, before, after, hot));
    }

    private record Witness(String run, String token) {
        @Override public String toString() { return "ReencryptionPrimary[redacted]"; }
    }
    private final class Context implements AutoCloseable {
        final Witness witness;
        final String token = UUID.randomUUID().toString(), newPass = UUID.randomUUID().toString();
        final UUID expected;
        RedisReencryptionTransport.Session session;
        Context(Witness witness, UUID expected) { this.witness = witness; this.expected = expected; }
        BoundedRedisWire.Reply call(BoundedRedisWire.Limits limits, String... args) {
            if (session == null) session = wire.connect();
            try { return session.call(limits, args); }
            catch (BoundedRedisWire.Failure failed) { close(); throw failed; }
        }
        public void close() { if (session != null) { session.close(); session = null; } }
    }
    private Object execute(Context c, String body, List<String> extraKeys, List<String> extraArgs) {
        var names = new ArrayList<>(List.of(cursorKey())); names.addAll(extraKeys);
        var args = new ArrayList<>(List.of(c.witness.run, target, c.witness.token, c.token,
                "30000", "604800", c.newPass, c.expected == null ? "" : c.expected.toString()));
        args.addAll(extraArgs);
        String program = HEADER + body;
        RedisProtectionArguments.validate(program, names, args); redis.requireSameSlot(names);
        var command = new ArrayList<>(List.of("EVAL", program, Integer.toString(names.size())));
        command.addAll(names); command.addAll(args);
        try {
            Object value = c.call(IO, command.toArray(String[]::new)).value();
            if (value instanceof List<?> error && error.size() == 2 && "~N1".equals(error.get(0)))
                throw fail(Code.valueOf(text(error.get(1))));
            return value;
        } catch (BoundedRedisWire.Failure failed) { throw wireFailure(failed); }
    }
    private BoundedRedisWire.Reply scan(Context c, String cursor) {
        int count = 128;
        while (true) {
            execute(c, OWNED + "renew(); return 1", List.of(), List.of());
            try {
                return c.call(SCAN, "SCAN", cursor, "MATCH",
                        glob(keys.entityKeyPrefix(metadata.redisNamespace())) + "*", "COUNT", Integer.toString(count));
            } catch (BoundedRedisWire.Failure failed) {
                if (failed.code() != BoundedRedisWire.Code.INPUT_LIMIT) throw wireFailure(failed);
                if (count == 1) throw fail(Code.INPUT_LIMIT);
                count = Math.max(1, count / 2); // Parser rejection closes the socket; retry SAME cursor.
            }
        }
    }

    /** Actual bounded primary/topology reads, never an invented tenant-generation authority. */
    private Witness witness() {
        try (var session = wire.connect()) {
            var role = list(session.call(IO, "ROLE").value());
            if (role.isEmpty() || !"master".equals(role.get(0))) throw fail(Code.TOPOLOGY_CHANGED);
            String info = text(session.call(IO, "INFO", "SERVER").value()), run = null;
            for (String line : info.split("\\r?\\n")) if (line.startsWith("run_id:")) run = line.substring(7);
            if (run == null || !run.matches("[0-9a-f]{40}")) throw fail(Code.TOPOLOGY_CHANGED);
            var parts = new ArrayList<String>(); parts.add(run);
            if (redis.clustered()) {
                String nodes = text(session.call(IO, "CLUSTER", "NODES").value());
                if (nodes.length() > 262144 || nodes.contains("[")) throw fail(Code.TOPOLOGY_CHANGED);
                long slot = ((Number) session.call(IO, "CLUSTER", "KEYSLOT", cursorKey()).value()).longValue();
                boolean owns = false;
                var stable = new ArrayList<String>();
                for (String line : nodes.split("\n")) {
                    if (stable.size() >= 128) throw fail(Code.INPUT_LIMIT);
                    String[] fields = line.trim().split(" +");
                    if (fields.length < 8) throw fail(Code.TOPOLOGY_CHANGED);
                    String flags = fields[2];
                    if (flags.contains("fail") || flags.contains("handshake") || flags.contains("noaddr"))
                        throw fail(Code.TOPOLOGY_CHANGED);
                    var slots = new ArrayList<String>();
                    for (int i = 8; i < fields.length; i++) {
                        String range = fields[i];
                        if (!range.matches("[0-9]{1,5}(-[0-9]{1,5})?")) throw fail(Code.TOPOLOGY_CHANGED);
                        slots.add(range);
                        if (flags.contains("myself") && flags.contains("master")) {
                            String[] ends = range.split("-");
                            if (slot >= Long.parseLong(ends[0]) && slot <= Long.parseLong(ends[ends.length - 1])) owns = true;
                        }
                    }
                    stable.add(fields[0] + ":" + fields[1] + ":" + flags + ":" + fields[6] + ":" + String.join(",", slots));
                }
                if (!owns) throw fail(Code.TOPOLOGY_CHANGED);
                Collections.sort(stable); parts.addAll(stable);
            }
            return new Witness(run, hash(parts));
        } catch (BoundedRedisWire.Failure failed) { throw wireFailure(failed); }
    }
    private record Frame(String next, List<String> keys) {}
    private record State(UUID pass, String cursor, String pending, int offset, boolean done,
            long total, long updated, long conflicts, long missing, long blocked, long growth) {}
    private static State state(Object raw) {
        var v = list(raw);
        if (v.size() != 11) throw fail(Code.CURSOR_MISMATCH);
        UUID pass = UUID.fromString(text(v.get(0)));
        String cursor = text(v.get(1)), pending = text(v.get(2)), done = text(v.get(4));
        long offset = number(v.get(3));
        if (!cursor.matches("[0-9]{1,20}") || pending.length() > 90000 || offset > 256
                || !Set.of("0", "1").contains(done)) throw fail(Code.CURSOR_MISMATCH);
        return new State(pass, cursor, pending, (int) offset, done.equals("1"),
                number(v.get(5)), number(v.get(6)), number(v.get(7)), number(v.get(8)), number(v.get(9)), number(v.get(10)));
    }
    private static Frame frame(String pending) {
        try {
            var v = list(BoundedRedisWire.decode(Base64.getDecoder().decode(pending), SCAN).value());
            if (v.size() != 2 || !text(v.get(0)).matches("[0-9]{1,20}")) throw fail(Code.CURSOR_MISMATCH);
            var entries = list(v.get(1));
            if (entries.size() > 256) throw fail(Code.INPUT_LIMIT);
            var keys = new ArrayList<String>();
            for (Object entry : entries) keys.add(text(entry));
            return new Frame(text(v.get(0)), List.copyOf(keys));
        } catch (RuntimeException unsafe) { throw fail(Code.CURSOR_MISMATCH); }
    }
    private static CacheKeyProvider pin(CacheKeyProvider provider, EntityMetadata<?, ?> metadata) {
        if (provider == null) throw fail(Code.UNBOUND_MAPPING);
        var current = new HashMap<String, KeyMaterial>();
        for (var descriptor : metadata.encryptedColumns().values())
            current.computeIfAbsent(descriptor.keyRing(), provider::current);
        if (current.size() != metadata.encryptedColumns().values().stream().map(EncryptedColumnDescriptor::keyRing).distinct().count())
            throw fail(Code.UNBOUND_MAPPING);
        var fixed = Map.copyOf(current);
        return new CacheKeyProvider() {
            public KeyMaterial current(String ring) { return fixed.get(ring); }
            public Optional<KeyMaterial> byId(String ring, String id) {
                KeyMaterial key = fixed.get(ring);
                return key != null && key.keyId().equals(id) ? Optional.of(key) : provider.byId(ring, id);
            }
        };
    }
    private static String target(EntityMetadata<?, ?> m, CacheKeyProvider p, QuotaPolicy q) {
        var parts = new ArrayList<>(List.of("cachedb:non-tenant-rotation:resident:v1", m.entityType().getName(),
                m.redisNamespace(), m.tableName(), m.idColumn(), Integer.toString(m.schemaVersion()), Boolean.toString(q.quotaFree())));
        parts.addAll(m.columns()); parts.addAll(new TreeSet<>(q.quotaColumns()));
        for (String column : new TreeSet<>(m.encryptedColumns().keySet())) {
            var d = m.encryptedColumns().get(column);
            parts.addAll(List.of(column, d.keyRing(), d.scope().name(), Boolean.toString(d.blindIndex()), p.current(d.keyRing()).keyId()));
        }
        return hash(parts);
    }
    private static String hash(List<String> parts) {
        try {
            var d = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                d.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII)); d.update(bytes);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (Exception unsafe) { throw fail(Code.UNBOUND_MAPPING); }
    }
    private static boolean same(Map<String, Object> a, Map<String, Object> b) {
        if (a == null || b == null || a.size() > 130 || !a.keySet().equals(b.keySet())) return false;
        for (String key : a.keySet()) if (!Objects.deepEquals(a.get(key), b.get(key))) return false;
        return true;
    }
    private static String tag(String key) {
        int open = key.indexOf('{'), close = open < 0 ? -1 : key.indexOf('}', open + 1);
        if (open < 0 || close <= open + 1) throw fail(Code.UNBOUND_MAPPING);
        return key.substring(open + 1, close);
    }
    private static String glob(String prefix) {
        return prefix.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?").replace("[", "\\[").replace("]", "\\]");
    }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> v)) throw fail(Code.CURSOR_MISMATCH);
        return v;
    }
    private static String text(Object value) {
        if (!(value instanceof String v)) throw fail(Code.CURSOR_MISMATCH);
        return v;
    }
    private static long number(Object value) {
        String s = text(value);
        if (!s.matches("0|[1-9][0-9]{0,11}")) throw fail(Code.CURSOR_MISMATCH);
        return Long.parseLong(s);
    }
    private static void positive(String value) {
        if (!value.matches("[1-9][0-9]{0,18}") || Long.parseLong(value) <= 0) throw fail(Code.UNBOUND_MAPPING);
    }
    private static Failure wireFailure(BoundedRedisWire.Failure f) {
        return fail(switch (f.code()) {
            case BUSY -> Code.BUSY;
            case INPUT_LIMIT -> Code.INPUT_LIMIT;
            case TOPOLOGY_CHANGED -> Code.TOPOLOGY_CHANGED;
            default -> Code.STORAGE_UNAVAILABLE;
        });
    }
    private static Failure fail(Code code) { return new Failure(code); }
}

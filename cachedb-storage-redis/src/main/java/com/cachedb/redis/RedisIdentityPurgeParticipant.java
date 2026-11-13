package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/**
 * Native identity cleanup only. The coordinator retains closure, discovery, queue and source authority.
 * Counts are per stable inventory position, not cumulative counters to add again on retry.
 */
public final class RedisIdentityPurgeParticipant implements PurgeParticipant {
    /**
     * Attestation from the existing coordinator, NOT a new generation/publication authority.
     * Must validate sealed full store/page/projection/schema inventory and drained original-generation
     * producers, reads/fills, local caches and every runtime's L1 acknowledgement on EACH call.
     * CLOSE does not require the identity worklist to be sealed; DISCOVER may still be in progress.
     * During VERIFY also requires the fresh bounded shared-membership/orphan audit to be complete.
     * Unknown runtimes, manifests, historical schema or mixed ownership return false.
     */
    @FunctionalInterface
    public interface Closure {
        boolean attest(Request request, String layoutFingerprint);
    }

    /** Stable immutable worklist. Pages must come from the coordinator's sealed discovery journal. */
    public interface Inventory {
        boolean sealed(Lease lease);
        List<PurgeWorkItem> page(Lease lease, String after, int limit, int maxBytes);
    }

    // Structural input bounds only. RedisGenerationBarrier remains the sole lease/generation authority.
    private static final String CONTROL_LIMITS = RedisInitialGenerationSchema.LUA + """
            local leaseType=redis.call('TYPE',KEYS[2]).ok
            if redis.call('TYPE',KEYS[1]).ok~='hash'
              or leaseType~='none' and leaseType~='string'
              or leaseType=='string' and redis.call('STRLEN',KEYS[2])>128 then return {-1} end
            if cachedb_initial_ledger(KEYS[1])==nil then return {-1} end
            -- Absence/expiry is lease loss, not unknown schema: let the canonical fence reject it.
            """;
    private static final String PROGRAM = CONTROL_LIMITS + RedisGenerationBarrier.closedCheckLua() + loadProgram();
    private static final String FENCE_PROBE = CONTROL_LIMITS + RedisGenerationBarrier.closedCheckLua() + "return 1";
    private final BoundedRedisWire wire;
    private final RedisClientOperations redis;
    private final RedisKeyStrategy keys;
    private final RedisGenerationBarrier barrier;
    private final String namespace, tenant, layout;
    private final Inventory inventory;
    private final Closure closure;
    private final List<RedisPurgeTarget> targets;
    private final Map<String, RedisNearCacheCoordinator.Store> localL1;
    private final Declaration declaration;

    /**
     * requiredNamespaces is the complete reviewed store manifest, not just available adapters.
     * expectedLayout is the coordinator binding's persisted layout token. Unknown/missing => INCOMPLETE.
     * Base target first; <=8 reviewed ID-preserving, entity-owned targets. No arbitrary shared projections.
     */
    public RedisIdentityPurgeParticipant(BoundedRedisWire wire, RedisClientOperations redis,
            RedisKeyStrategy keys, RedisGenerationBarrier barrier, String namespace, String tenant,
            String entityName, String expectedLayout, Set<String> requiredNamespaces,
            List<RedisPurgeTarget> targets, Inventory inventory, Closure closure,
            Map<String, RedisNearCacheCoordinator.Store> localL1) {
        this.wire = Objects.requireNonNull(wire); this.redis = Objects.requireNonNull(redis);
        this.keys = Objects.requireNonNull(keys); this.barrier = Objects.requireNonNull(barrier);
        ProtectionKey.checkTenant(tenant); this.namespace = namespace; this.tenant = tenant;
        try { RedisProtectionArguments.layout(barrier, keys, namespace); }
        catch (ProtectionException unknown) { throw incomplete(); }
        this.inventory = Objects.requireNonNull(inventory); this.closure = Objects.requireNonNull(closure);
        if (targets == null || targets.isEmpty() || targets.size() > 8 || localL1 == null || localL1.size() > 8)
            throw incomplete();
        if (keys.getClass() != RedisKeyStrategy.class && keys.getClass() != ClusterHashTagKeyStrategy.class)
            throw incomplete();
        this.targets = List.copyOf(targets); this.localL1 = Map.copyOf(localL1);
        if (!targets.getFirst().namespace().equals(namespace) || targets.getFirst().projection) throw incomplete();
        var actual = new TreeSet<String>();
        for (var target : targets)
            if (!actual.add(target.namespace()) || !target.identityPreserving || target.indexes.keys != keys)
                throw incomplete();
        if (!actual.equals(requiredNamespaces) || !actual.containsAll(localL1.keySet())) throw incomplete();
        for (var target : targets)
            if (target.indexes.metadata.nearCachePolicy().enabled() && !localL1.containsKey(target.namespace()))
                throw incomplete();
        layout = layoutFingerprint(targets);
        if (!layout.equals(expectedLayout)) throw incomplete();
        declaration = new Declaration("native-redis-identities", layout, Set.of(entityName), false);
    }

    public static String layoutFingerprint(List<RedisPurgeTarget> targets) {
        if (targets == null || targets.isEmpty() || targets.size() > 8) throw incomplete();
        var value = new StringBuilder("native-redis-purge-v1");
        targets.stream().sorted(Comparator.comparing(RedisPurgeTarget::namespace)).forEach(target -> {
            RedisPurgeIndexLayout.add(value, target.namespace());
            RedisPurgeIndexLayout.add(value, target.fingerprint());
        });
        return ProtectionKey.hash(value.toString());
    }

    @Override public Declaration declaration() { return declaration; }

    @Override public Result step(Request request) {
        if (!CacheTenantContext.isAdministrative())
            throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);
        String checkpoint = request.checkpoint();
        if (!barrier.key(tenant).equals(request.lease().key())) throw incomplete();
        try {
            // This attestation cannot be replaced by Pub/Sub delivery or a Java generation GET.
            if (!closure.attest(request, layout)) return blocked(checkpoint);
            if (request.phase() != Phase.CLOSE && !inventory.sealed(request.lease())) {
                return request.phase() == Phase.DISCOVER
                        ? new Result(State.MORE, checkpoint, 0, 0, "") : blocked(checkpoint);
            }
            if (request.phase() == Phase.CLOSE || request.phase() == Phase.DISCOVER) {
                fencedProbe(request);
                if (request.phase() == Phase.CLOSE) {
                    targets.forEach(target -> target.invalidateLocal.accept(null));
                }
                return new Result(State.VERIFIED, checkpoint, 0, 0, evidence(request, checkpoint));
            }
            String prefix = request.phase() == Phase.ERASE ? "e:" : "v:";
            String after = checkpoint.isEmpty() ? null : cursor(checkpoint, prefix);
            // One identity is a deliberate hard per-call CPU/I/O bound; no unbounded multi-ID Lua loop.
            var page = inventory.page(request.lease(), after, 1, request.maxBytes());
            if (page.isEmpty()) {
                fencedProbe(request);
                if (!closure.attest(request, layout)) return blocked(checkpoint);
                return new Result(State.VERIFIED, checkpoint, 0, 0, evidence(request, checkpoint));
            }
            if (page.size() != 1) throw incomplete();
            var work = page.getFirst();
            if (work.kind() != PurgeWorkItem.Kind.ID) return blocked(checkpoint);
            String id = identity(work);
            if (!request.lease().key().objectToken(id).equals(work.token())
                    || after != null && work.token().compareTo(after) <= 0) throw incomplete();
            long removed;
            localInvalidate(id);
            try {
                removed = request.phase() == Phase.ERASE ? erase(request, work.token(), id) : verify(request, work.token(), id);
            } finally {
                // Also run after unknown outcomes. Never release an I/O permit before physical call exit.
                localInvalidate(id);
            }
            if (!closure.attest(request, layout)) return blocked(checkpoint);
            return new Result(State.MORE, prefix + work.token(), 1, removed, "");
        } catch (ProtectionException safe) {
            if (safe.code() == ProtectionException.Code.INCOMPLETE_INVENTORY) return blocked(checkpoint);
            throw safe;
        } catch (RuntimeException failure) {
            // Redis/provider exception causes can contain business keys, values or credentials.
            throw new ProtectionException(ProtectionException.Code.STORAGE_UNAVAILABLE);
        }
    }

    private void localInvalidate(String id) {
        for (var target : targets) target.invalidateLocal.accept(id);
        for (var store : localL1.values()) store.invalidate(id);
    }

    private long erase(Request request, String token, String id) {
        var snapshot = snapshot(request, token, id);
        if (snapshot.receipt != null) return count(snapshot.receipt, request);
        var rpc = command(request, token, id, "E", snapshot);
        String hot = snapshot.hot;
        RedisPurgeTarget base = targets.getFirst();
        String tenantSet = base.tenantColumn == null ? null : keys.tenantHotSetKey(namespace, base.tenantColumn, tenant);
        String bytesKey = base.tenantColumn == null ? null : keys.tenantHotPayloadBytesKey(namespace, base.tenantColumn, tenant);
        long charge = RedisPageCacheManager.purgeTenantPayloadBytes(hot, tenantSet, bytesKey);
        rpc.args.add(hot == null ? "0" : "1"); rpc.args.add(hot == null ? "" : hot);
        rpc.args.add(Integer.toString(tenantSet == null ? 0 : rpc.key(tenantSet)));
        rpc.args.add(Integer.toString(bytesKey == null ? 0 : rpc.key(bytesKey)));
        rpc.args.add(Integer.toString(tenantSet == null ? 0 : rpc.key(RedisPageCacheManager.tenantAdmissionLockKey(tenantSet))));
        rpc.args.add(Long.toString(charge));
        var actions = new Actions();
        for (int i = 0; i < targets.size(); i++) {
            var target = targets.get(i);
            for (var owned : target.ownedKeys(id)) add(actions, owned, "D");
            for (var member : target.memberships(id, tenant, snapshot.metadata.get(i))) add(actions, member, "M");
        }
        if (tenantSet != null) add(actions, new RedisPurgeTarget.Action(tenantSet, "zset"), "M");
        if (bytesKey != null) add(actions, new RedisPurgeTarget.Action(bytesKey, "string"), "C");
        rpc.args.add(Integer.toString(actions.size()));
        for (var action : actions.values()) {
            int index = rpc.key(action.key);
            if (index <= 3) throw incomplete();
            rpc.args.add(Integer.toString(index)); rpc.args.add(action.type); rpc.args.add(action.kind);
        }
        Object result = call(request, rpc);
        if (!(result instanceof String receipt)) throw incomplete();
        return count(receipt, request);
    }

    private long verify(Request request, String token, String id) {
        var rpc = command(request, token, id, "V", null);
        var owned = new LinkedHashSet<String>();
        for (var target : targets) for (var key : target.ownedKeys(id)) owned.add(key.key());
        rpc.args.add(Integer.toString(owned.size()));
        owned.forEach(key -> rpc.args.add(Integer.toString(rpc.key(key))));
        var memberships = new ArrayList<RedisPurgeTarget.Action>();
        for (var target : targets) memberships.addAll(target.memberships(id, tenant, Map.of()));
        var base = targets.getFirst();
        if (base.tenantColumn != null) memberships.add(new RedisPurgeTarget.Action(
                keys.tenantHotSetKey(namespace, base.tenantColumn, tenant), "zset"));
        rpc.args.add(Integer.toString(memberships.size()));
        for (var member : memberships) {
            rpc.args.add(Integer.toString(rpc.key(member.key()))); rpc.args.add(member.type());
        }
        if (!(call(request, rpc) instanceof String receipt)) throw incomplete();
        count(receipt, request);
        // Shared exact/text/composite keys are re-audited using the sealed scanner work, not guessed from deleted metadata.
        return 0;
    }

    private record Snapshot(String receipt, String hot, List<Map<String, String>> metadata) {
        @Override public String toString() { return "PurgeSnapshot[redacted]"; }
    }
    private Snapshot snapshot(Request request, String token, String id) {
        var rpc = command(request, token, id, "S", null);
        Object raw = call(request, rpc);
        if (!(raw instanceof List<?> values) || values.isEmpty() || !(values.getFirst() instanceof Long code)) throw incomplete();
        if (code == 1 && values.size() == 2 && values.get(1) instanceof String receipt)
            return new Snapshot(receipt, null, List.of());
        if (code != 0 || values.size() != 2 + targets.size()) throw incomplete();
        String hot = textOrNull(values.get(1));
        var metadata = new ArrayList<Map<String, String>>();
        for (int i = 0; i < targets.size(); i++) {
            if (!(values.get(i + 2) instanceof List<?> fields) || fields.size() != targets.get(i).indexes.fields.size())
                throw incomplete();
            var row = new LinkedHashMap<String, String>();
            for (int j = 0; j < fields.size(); j++) {
                String value = textOrNull(fields.get(j));
                if (value != null) row.put(targets.get(i).indexes.fields.get(j), value);
            }
            metadata.add(Map.copyOf(row));
        }
        return new Snapshot(null, hot, List.copyOf(metadata));
    }

    private Rpc command(Request request, String token, String id, String mode, Snapshot snapshot) {
        var rpc = new Rpc();
        for (String key : barrier.fencedKeys(request.lease())) rpc.key(key);
        rpc.key(receiptKey(request.lease(), token));
        int owner = rpc.key(keys.versionKey(namespace, id) + ":tenant");
        int hot = rpc.key(keys.tenantHotOwnerKey(namespace, id));
        rpc.args.addAll(barrier.fencedArgs(request.lease(), request.generation()));
        Collections.addAll(rpc.args, mode, layout, id, tenant, Integer.toString(owner), Integer.toString(hot));
        if (mode.equals("V")) return rpc;
        rpc.args.add(Integer.toString(targets.size()));
        for (int i = 0; i < targets.size(); i++) {
            var index = targets.get(i).indexes;
            rpc.args.add(Integer.toString(rpc.key(keys.indexMetaKey(targets.get(i).namespace(), id))));
            rpc.args.add(Integer.toString(index.fields.size()));
            rpc.args.add(Integer.toString(rpc.key(keys.compositeIndexSignatureKey(targets.get(i).namespace()))));
            rpc.args.add(index.metadata.compositeExactIndexes().isEmpty() ? "" : RedisCompositeIndexes.signature(index.metadata));
            if (index.migrationKeys.size() != 2) throw incomplete();
            rpc.args.add(Integer.toString(rpc.key(index.migrationKeys.get(0))));
            rpc.args.add(Integer.toString(rpc.key(index.migrationKeys.get(1))));
            rpc.args.add(Integer.toString(rpc.key(keys.indexDegradedKey(targets.get(i).namespace()))));
            rpc.args.add(Integer.toString(rpc.key(keys.entityKey(targets.get(i).namespace(), id))));
            rpc.args.add(Integer.toString(rpc.key(keys.indexAllKey(targets.get(i).namespace()))));
            for (String field : index.fields) {
                rpc.args.add(field);
                if (mode.equals("E")) {
                    String value = snapshot.metadata.get(i).get(field);
                    rpc.args.add(value == null ? "0" : "1"); rpc.args.add(value == null ? "" : value);
                }
            }
        }
        return rpc;
    }

    public String receiptKey(Lease lease, String identityToken) {
        ProtectionKey.token(identityToken);
        return keys.namespacePrefix(namespace) + ":purge:" + lease.key().tenantToken()
                + ":" + lease.operationId() + ":native:" + identityToken;
    }
    private void fencedProbe(Request request) {
        var rpc = new Rpc(); barrier.fencedKeys(request.lease()).forEach(rpc::key);
        rpc.args.addAll(barrier.fencedArgs(request.lease(), request.generation()));
        Object result = call(request, rpc, FENCE_PROBE);
        if (!(result instanceof Long value) || value != 1L) throw incomplete();
    }
    private Object call(Request request, Rpc rpc) { return call(request, rpc, PROGRAM); }
    private Object call(Request request, Rpc rpc, String program) {
        var actualKeys = List.copyOf(rpc.keys.keySet());
        redis.requireSameSlot(actualKeys);
        RedisProtectionArguments.validate(program, actualKeys, rpc.args);
        var args = new ArrayList<String>(actualKeys.size() + rpc.args.size() + 3);
        Collections.addAll(args, "EVAL", program, Integer.toString(actualKeys.size()));
        args.addAll(actualKeys); args.addAll(rpc.args);
        long bytes = 128L + program.length();
        for (String value : actualKeys) bytes += value.getBytes(StandardCharsets.UTF_8).length + 32L;
        for (String value : rpc.args) bytes += value.getBytes(StandardCharsets.UTF_8).length + 32L;
        if (bytes > request.maxBytes() || args.size() > 8192 || actualKeys.size() > 4096) throw incomplete();
        var limits = new BoundedRedisWire.Limits(Math.max(1024, request.maxBytes()), Math.min(16384, request.maxBytes()), 8192,
                Math.max(1024, request.maxBytes()));
        try (var session = wire.connect()) {
            return session.call(limits, args.toArray(String[]::new)).value();
        }
    }
    private static final class Rpc {
        final LinkedHashMap<String, Integer> keys = new LinkedHashMap<>();
        final ArrayList<String> args = new ArrayList<>();
        long keyBytes;
        int key(String key) {
            if (key == null || key.length() > 8192) throw incomplete();
            Integer existing = keys.get(key);
            if (existing != null) return existing;
            keyBytes += key.length() * 3L + 64;
            if (keyBytes > 256 * 1024 || keys.size() >= 4096) throw incomplete();
            int index = keys.size() + 1; keys.put(key, index); return index;
        }
    }
    private record Action(String key, String type, String kind) {
        @Override public String toString() { return "PurgeAction[redacted]"; }
    }
    private static final class Actions extends LinkedHashMap<String, Action> { long keyBytes; }
    private static void add(Actions actions, RedisPurgeTarget.Action value, String kind) {
        var action = new Action(value.key(), value.type(), kind);
        var prior = actions.putIfAbsent(value.key(), action);
        if (prior == null) actions.keyBytes += value.key().length() * 3L + 64;
        if (actions.keyBytes > 256 * 1024) throw incomplete();
        if (prior != null && !prior.equals(action) || actions.size() > 2048) throw incomplete();
    }
    private long count(String receipt, Request request) {
        String[] parts = receipt.split("\\|", -1);
        if (parts.length != 4 || !parts[0].equals(layout) || !parts[1].equals(Long.toString(request.generation()))
                || !parts[2].matches("0|[1-9][0-9]{0,3}") || !parts[3].matches("0|[1-9][0-9]{0,6}")) throw incomplete();
        long keys = Long.parseLong(parts[2]);
        if (keys > 2048) throw incomplete();
        return keys;
    }
    private static String cursor(String checkpoint, String prefix) {
        if (!checkpoint.startsWith(prefix) || checkpoint.length() != prefix.length() + 64) throw incomplete();
        return ProtectionKey.token(checkpoint.substring(prefix.length()));
    }
    private static String identity(PurgeWorkItem work) {
        byte[] raw = work.locator();
        if (raw.length == 0 || raw.length > 1024) throw incomplete();
        try {
            String id = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
            if (!id.matches("[A-Za-z0-9._-]{1,1024}")) throw incomplete();
            return id;
        } catch (CharacterCodingException invalid) { throw incomplete(); }
    }
    private static String textOrNull(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) return null;
        if (value instanceof String text) return text;
        throw incomplete();
    }
    private String evidence(Request request, String checkpoint) {
        return ProtectionKey.hash("native-redis-purge-v1:" + request.lease().operationId()
                + ":" + request.generation() + ":" + layout + ":" + request.phase() + ":" + checkpoint);
    }
    private static Result blocked(String checkpoint) { return new Result(State.INCOMPLETE, checkpoint, 0, 0, ""); }
    static ProtectionException incomplete() { return new ProtectionException(ProtectionException.Code.INCOMPLETE_INVENTORY); }
    private static String loadProgram() {
        try (var input = RedisIdentityPurgeParticipant.class.getResourceAsStream("/functions/cachedb-purge-identity.lua")) {
            if (input == null) throw incomplete();
            byte[] bytes = input.readNBytes(65537);
            if (bytes.length > 65536) throw incomplete();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException unavailable) { throw incomplete(); }
    }
}

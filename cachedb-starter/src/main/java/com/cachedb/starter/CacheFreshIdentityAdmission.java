package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.redis.*;
import com.reactor.cachedb.jdbc.JdbcOpenActivationGrant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** Root-private, one-shot owner provisioning on the same physical primary observed before SQL CAS. */
final class CacheFreshIdentityAdmission {
    private static final int MAX_IDENTITIES = 128;
    private static final String SCRIPT = loadScript();
    private final RedisTenantPurgePlan plan;
    private final FreshIdentityRange.Request request;
    private final String tenant;
    private final List<String> keys, roster;
    private boolean dispatched;

    CacheFreshIdentityAdmission(RedisTenantPurgePlan plan, InitialSourceHotWindow<?, ?> window,
            RedisTenantRuntimeControl.ApprovedRoster approval, UUID runtime, String tenant,
            FreshIdentityRange.Request request) {
        this.plan = Objects.requireNonNull(plan); this.request = Objects.requireNonNull(request);
        ProtectionKey.checkTenant(tenant); this.tenant = tenant;
        if (request.count() > MAX_IDENTITIES || !plan.binding.key(tenant).equals(request.generation().key())
                || !plan.binding.fingerprint().equals(request.domain().bindingFingerprint())
                || !plan.binding.token().equals(request.domain().scopeToken())) fail(INVALID_REQUEST);
        if (plan.redis.clustered() || plan.targets.size() != 1
                || !approval.pairs().contains(request.generation().key()) || !approval.members().contains(runtime))
            fail(UNWIRED_PROTOCOL);
        // The supported INITIAL codec is canonical Long. Validate the entire attested domain before SQL.
        canonical(window, request.domain().first()); canonical(window, request.domain().last());
        var expected = new TreeMap<String, String>();
        expected.put("schema", "2"); expected.put("state", "SEALED");
        expected.put("approval", approval.approvalToken()); expected.put("manifest", approval.manifestToken());
        expected.put("coverage", approval.coverageToken());
        expected.put("required", Integer.toString(approval.members().size()));
        expected.put("enrolled", Integer.toString(approval.members().size()));
        expected.put("pairs", Integer.toString(approval.pairs().size()));
        for (var member : approval.members()) {
            expected.put("e:" + member, "1"); expected.put("m:" + member, "1");
        }
        for (var pair : approval.pairs()) expected.put("p:" + pair.tenantToken(), "1");
        var fields = new ArrayList<String>(); fields.add(Integer.toString(expected.size()));
        expected.forEach((field, value) -> { fields.add(field); fields.add(value); });
        roster = List.copyOf(fields);
        keys = List.of(plan.barrier.generationKey(request.generation().key()),
                RedisTenantRuntimeControl.rosterKey(plan.keys, plan.scope()),
                plan.barrier.versionAllocatorKey(request.generation().key()),
                plan.barrier.versionAllocatorKey(request.generation().key()) + ":next");
    }

    String capture(BoundedRedisWire.Session physical) {
        Object role = physical.call(BoundedRedisWire.Limits.entries(), "ROLE").value();
        if (!(role instanceof List<?> values) || values.isEmpty() || !"master".equals(values.getFirst()))
            throw new ProtectionException(RECOVERY_REQUIRED);
        String cluster = text(physical.call(BoundedRedisWire.Limits.entries(), "INFO", "CLUSTER").value());
        if (!cluster.contains("cluster_enabled:0\r\n")) fail(UNWIRED_PROTOCOL);
        String info = text(physical.call(BoundedRedisWire.Limits.entries(), "INFO", "SERVER").value());
        for (String line : info.split("\r\n"))
            if (line.matches("run_id:[0-9a-f]{40}")) return line.substring(7);
        throw new ProtectionException(RECOVERY_REQUIRED);
    }

    void admit(BoundedRedisWire.Session physical, String run, FreshIdentityRange range, JdbcOpenActivationGrant grant) {
        if (dispatched || !request.equals(range.request()) || !run.matches("[0-9a-f]{40}")) fail(RECOVERY_REQUIRED);
        var version = grant.range();
        if (!version.runId().equals(run) || !version.key().equals(request.generation().key())
                || version.generation() != request.generation().value()) fail(RECOVERY_REQUIRED);
        dispatched = true;
        var routed = new ArrayList<>(keys);
        for (long id = range.first(); id <= range.last(); id++) {
            String ns = plan.scope(); var layout = plan.keys;
            routed.addAll(List.of(layout.entityKey(ns, id), layout.versionKey(ns, id),
                    layout.versionKey(ns, id) + ":tenant", layout.tombstoneKey(ns, id),
                    layout.indexMetaKey(ns, id), layout.durabilityKey(ns, id), layout.tenantHotOwnerKey(ns, id),
                    layout.compactionPayloadKey(ns, id), layout.compactionPendingKey(ns, id)));
        }
        // Standalone is explicit; an accidental layout collision must not turn a control key into an owner.
        if (new HashSet<>(routed).size() != routed.size()) fail(UNWIRED_PROTOCOL);
        var command = new ArrayList<String>();
        command.add("EVAL"); command.add(SCRIPT); command.add(Integer.toString(routed.size())); command.addAll(routed);
        command.addAll(List.of(run, Long.toString(request.generation().value()), grant.generationFields().get("epoch"),
                tenant, Integer.toString(request.count())));
        command.addAll(roster);
        command.addAll(List.of(Long.toString(version.first()-1), Long.toString(version.last()), version.inventory(),
                version.request().toString(), Long.toString(version.reservationEpoch()),
                Integer.toString(grant.generationFields().size())));
        new TreeMap<>(grant.generationFields()).forEach((field,value) -> {command.add(field);command.add(value);});
        Object result = physical.call(new BoundedRedisWire.Limits(4096, 2048, 64, 524288),
                command.toArray(String[]::new)).value();
        if (!(result instanceof Number number) || number.longValue() != 1) fail(RECOVERY_REQUIRED);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void canonical(InitialSourceHotWindow window, long value) {
        if (!Long.toString(value).equals(window.canonical(window.ids().decode(Long.toString(value))))) fail(INVALID_REQUEST);
    }
    private static String text(Object value) {
        if (!(value instanceof String result) || result.length() > 262144) throw new ProtectionException(RECOVERY_REQUIRED);
        return result;
    }
    private static String loadScript() {
        try (var input = CacheFreshIdentityAdmission.class.getResourceAsStream("/functions/cachedb-fresh-owner-admission.lua")) {
            if (input == null) throw new IllegalStateException("Fresh identity program unavailable");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unavailable) { throw new ExceptionInInitializerError(unavailable); }
    }
    private static void fail(ProtectionException.Code code) { throw new ProtectionException(code); }
}

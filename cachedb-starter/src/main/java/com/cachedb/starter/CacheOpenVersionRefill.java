package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Generation;
import com.reactor.cachedb.jdbc.JdbcOpenActivationGrant;
import com.reactor.cachedb.redis.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** Root-private framing. A caller-constructed range or grant is never an installation capability. */
final class CacheOpenVersionRefill {
    private static final String SCRIPT=loadScript();
    private final Generation generation;
    private final List<String> keys,roster;
    private boolean dispatched;

    CacheOpenVersionRefill(RedisTenantPurgePlan plan,RedisTenantRuntimeControl.ApprovedRoster approval,
            UUID runtime,String tenant,Generation generation) {
        this.generation=Objects.requireNonNull(generation);
        ProtectionKey.checkTenant(tenant);
        if(!plan.binding.key(tenant).equals(generation.key())) fail(INVALID_REQUEST);
        if(plan.redis.clustered() || plan.targets.size()!=1 || !approval.pairs().contains(generation.key())
                || !approval.members().contains(runtime)) fail(UNWIRED_PROTOCOL);
        var expected=new TreeMap<String,String>();
        expected.put("schema","2"); expected.put("state","SEALED");
        expected.put("approval",approval.approvalToken()); expected.put("manifest",approval.manifestToken());
        expected.put("coverage",approval.coverageToken());
        expected.put("required",Integer.toString(approval.members().size()));
        expected.put("enrolled",Integer.toString(approval.members().size()));
        expected.put("pairs",Integer.toString(approval.pairs().size()));
        for(var member:approval.members()) { expected.put("e:"+member,"1"); expected.put("m:"+member,"1"); }
        for(var pair:approval.pairs()) expected.put("p:"+pair.tenantToken(),"1");
        var fields=new ArrayList<String>(); fields.add(Integer.toString(expected.size()));
        expected.forEach((field,value)->{fields.add(field);fields.add(value);});
        roster=List.copyOf(fields);
        keys=List.of(plan.barrier.generationKey(generation.key()),RedisTenantRuntimeControl.rosterKey(plan.keys,plan.scope()),
                plan.barrier.versionAllocatorKey(generation.key()),plan.barrier.versionAllocatorKey(generation.key())+":next");
        if(new HashSet<>(keys).size()!=keys.size()) fail(UNWIRED_PROTOCOL);
    }

    String capture(BoundedRedisWire.Session physical) {
        Object role=physical.call(BoundedRedisWire.Limits.entries(),"ROLE").value();
        if(!(role instanceof List<?> values) || values.isEmpty() || !"master".equals(values.getFirst())) fail(RECOVERY_REQUIRED);
        if(!text(physical.call(BoundedRedisWire.Limits.entries(),"INFO","CLUSTER").value())
                .contains("cluster_enabled:0\r\n")) fail(UNWIRED_PROTOCOL);
        for(String line:text(physical.call(BoundedRedisWire.Limits.entries(),"INFO","SERVER").value()).split("\r\n"))
            if(line.matches("run_id:[0-9a-f]{40}")) return line.substring(7);
        throw new ProtectionException(RECOVERY_REQUIRED);
    }

    void install(BoundedRedisWire.Session physical,String run,TenantVersionRange range,JdbcOpenActivationGrant grant) {
        var actual=grant.range();
        if(dispatched || !run.matches("[0-9a-f]{40}") || !run.equals(range.runId())
                || !generation.key().equals(range.key()) || generation.value()!=range.generation()
                || !sameRange(range,actual)) fail(RECOVERY_REQUIRED);
        String nativeEpoch=grant.generationFields().get("epoch");
        if(nativeEpoch==null || grant.generationFields().size()>16) fail(RECOVERY_REQUIRED);
        var command=new ArrayList<String>();
        command.addAll(List.of("EVAL",SCRIPT,Integer.toString(keys.size()))); command.addAll(keys);
        command.addAll(List.of(run,Long.toString(range.generation()),nativeEpoch,Long.toString(range.controlEpoch()),
                Long.toString(range.reservationEpoch()),Long.toString(range.first()-1),Long.toString(range.last()),
                range.request().toString(),range.inventory()));
        command.addAll(roster);
        command.add(Integer.toString(grant.generationFields().size()));
        new TreeMap<>(grant.generationFields()).forEach((field,value)->{command.add(field);command.add(value);});
        dispatched=true;
        Object result=physical.call(new BoundedRedisWire.Limits(4096,2048,64,524288),command.toArray(String[]::new)).value();
        if(!(result instanceof Number number) || number.longValue()<0 || number.longValue()>1) fail(RECOVERY_REQUIRED);
    }

    private static boolean sameRange(TenantVersionRange a,TenantVersionRange b) {
        return a.key().equals(b.key()) && a.generation()==b.generation() && a.controlEpoch()==b.controlEpoch()
                && a.reservationEpoch()==b.reservationEpoch() && a.first()==b.first() && a.last()==b.last()
                && a.request().equals(b.request()) && a.runId().equals(b.runId()) && a.inventory().equals(b.inventory());
    }
    private static String text(Object value) {
        if(!(value instanceof String result) || result.length()>262144) throw new ProtectionException(RECOVERY_REQUIRED);
        return result;
    }
    private static String loadScript() {
        try(var input=CacheOpenVersionRefill.class.getResourceAsStream("/functions/cachedb-open-version-refill.lua")) {
            if(input==null) throw new IllegalStateException("OPEN version refill program unavailable");
            byte[] bytes=input.readNBytes(65537);
            if(bytes.length>65536) throw new IllegalStateException("OPEN version refill program exceeds limit");
            return new String(bytes,StandardCharsets.UTF_8);
        } catch(IOException unavailable) { throw new ExceptionInInitializerError(unavailable); }
    }
    private static void fail(ProtectionException.Code code) { throw new ProtectionException(code); }
}

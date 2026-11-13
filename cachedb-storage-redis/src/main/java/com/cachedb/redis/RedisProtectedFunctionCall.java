package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.RedisFunctionsConfig;
import com.reactor.cachedb.core.model.WriteOperation;
import com.reactor.cachedb.core.protection.ProtectionException;
import java.util.ArrayList;
import java.util.List;

/** Pure canonical function framing. Original generation comes only from the physical producer ticket. */
final class RedisProtectedFunctionCall {
    record Frame(List<String> keys, List<String> args) {}

    static Frame prepare(RedisClientOperations redis, RedisFunctionsConfig config, RedisKeyStrategy runtimeKeys,
            com.reactor.cachedb.core.config.WriteBehindConfig writeBehind,
            List<String> keys, List<String> args, WriteOperation<?, ?> operation, boolean delete) {
        if (!operation.metadata().tenantPurgeEnabled()) throw new IllegalArgumentException("Protected metadata required");
        if (!config.enabled() || !"/functions/cachedb-functions.lua".equals(config.templateResourcePath())
                || config.sourceOverride() != null && !config.sourceOverride().isBlank())
            throw new ProtectionException(ProtectionException.Code.UNWIRED_PROTOCOL);
        String tenant = RedisTenantWriteGuard.tenant(operation);
        if (tenant == null) throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        String namespace = operation.metadata().redisNamespace();
        var original = RedisTenantProducer.current(namespace, tenant);
        var barrier = RedisTenantProducer.currentBarrier(namespace, tenant);
        // Validate the executor's actual runtime and canonical entity routes BEFORE appending a foreign guard.
        barrier.requireFunctionFrame(redis, runtimeKeys, writeBehind, operation, keys);
        var routed = new ArrayList<>(keys);
        int generationIndex = routed.size() + 1;
        routed.add(barrier.generationKey(original.key()));
        String allocator = barrier.versionAllocatorKey(original.key());
        int allocatorIndex = routed.size() + 1;
        routed.add(allocator);
        int counterIndex = routed.size() + 1;
        routed.add(allocator + ":next");
        var admission = barrier.admission();
        int membershipIndex = routed.size() + 1;
        if (admission != null) routed.add(admission.key);
        routed.add(keys.get(1) + ":tenant"); // Canonical owner marker remains LAST.
        var framed = new ArrayList<>(args);
        int trailer = delete ? 21 : Math.addExact(29, Math.multiplyExact(2, Integer.parseInt(args.get(19))));
        while (framed.size() < trailer) framed.add("");
        if (framed.size() != trailer) throw new ProtectionException(ProtectionException.Code.INVALID_REQUEST);
        framed.addAll(List.of("cachedb:generation:v1", Integer.toString(generationIndex),
                Long.toString(original.value()), original.key().tenantToken(), original.key().scopeToken(),
                "cachedb:version-range:v1", Integer.toString(allocatorIndex), Integer.toString(counterIndex)));
        if (admission != null) framed.addAll(List.of("cachedb:runtime:v1",
                Integer.toString(membershipIndex), admission.runtimeId.toString()));
        RedisProtectionArguments.validate("return 1", routed, framed);
        redis.requireSameSlot(routed);
        return new Frame(List.copyOf(routed), List.copyOf(framed));
    }
    private RedisProtectedFunctionCall() {}
}

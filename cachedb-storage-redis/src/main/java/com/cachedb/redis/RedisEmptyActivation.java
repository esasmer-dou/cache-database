package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.EmptyTenantActivation.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Separate native EMPTY protocol; deliberately unrelated to source-owner/INITIAL bootstrap. */
final class RedisEmptyActivation {
    private RedisEmptyActivation() {}
    private static final String SCRIPT = load();
    private static String load() {
        try (var in = RedisEmptyActivation.class.getResourceAsStream("/functions/cachedb-empty-activation.lua")) {
            if (in == null) throw new IllegalStateException("Missing EMPTY activation script");
            return RedisTenantVersionAllocator.library() + new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    static Object execute(RedisClientOperations redis, List<String> keys, String command, Checkpoint cp, Lease lease) {
        if (!CacheTenantContext.isAdministrative()) throw new ProtectionException(ProtectionException.Code.ADMIN_REQUIRED);
        if (lease != null) cp.requireLease(lease);
        var id = cp.identity(); var range = cp.reservation();
        var args = List.of(command, Long.toString(id.fromGeneration()), Long.toString(id.toGeneration()),
                id.operationId().toString(), cp.attemptOwner().toString(), Long.toString(cp.attemptEpoch()),
                id.operationId() + ":" + cp.attemptOwner() + ":" + cp.attemptEpoch(),
                Integer.toString(lease == null ? 1 : lease.seconds() * 1000), id.token(), cp.phase().name(),
                range == null ? "-" : range.runId(), range == null ? "0" : Long.toString(range.reservationEpoch()),
                range == null ? "0" : Long.toString(range.first()), range == null ? "0" : Long.toString(range.last()),
                range == null ? "-" : range.request().toString(), id.inventoryToken(), range == null ? "-" : cp.receiptToken());
        redis.requireSameSlot(keys); RedisProtectionArguments.validate(SCRIPT, keys, args);
        return redis.eval(SCRIPT, keys, args);
    }
    static Receipt receipt(Object value, Checkpoint cp) {
        if (!(value instanceof List<?> list) || list.size() != 4) throw new ProtectionException(ProtectionException.Code.RECOVERY_REQUIRED);
        try {
            Receipt receipt = new Receipt(cp.identity().token(), (String) list.get(3), (String) list.get(0),
                    Long.parseLong((String) list.get(1)), UUID.fromString((String) list.get(2)));
            receipt.require(cp); return receipt;
        } catch (ClassCastException | IllegalArgumentException invalid) {
            throw new ProtectionException(ProtectionException.Code.RECOVERY_REQUIRED);
        }
    }
}

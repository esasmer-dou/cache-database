package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.Lease;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Actual root-owned producer/read/L1 participant. It is not another generation authority. */
public final class RedisTenantRuntimeParticipant implements RedisNativeTenantReadFence.NativeClosureProbe {
    private final RedisTenantProducer producer;
    private final RedisTenantReadFence local;
    private final RedisTenantRuntimeControl control;
    private final BooleanSupplier coverage;
    private final RedisClientOperations redis;

    public RedisTenantRuntimeParticipant(RedisClientOperations redis, RedisKeyStrategy keys,
            RedisTenantProducer producer, BooleanSupplier actualCoverage) {
        this.producer = Objects.requireNonNull(producer); this.redis = redis; local = producer.readFence();
        control = new RedisTenantRuntimeControl(redis, keys, producer);
        coverage = Objects.requireNonNull(actualCoverage);
    }
    public UUID runtimeId() { return local.runtimeId(); }
    public RedisGenerationBarrier barrier() { return producer.barrier(); }
    public RedisTenantRuntimeControl control() { return control; }

    @Override public RedisNativeTenantReadFence.Probe inspect(Lease lease, long generation, String manifest) {
        control.requireSealed();
        if (!coverage.getAsBoolean() || !control.approval().manifestToken().equals(manifest)) return probe(TenantReadFence.State.INCOMPLETE, null);
        barrier().assertClosed(lease, generation);
        var producers = producer.drain(lease.key());
        if (producers.uncertain() != 0) return probe(TenantReadFence.State.INCOMPLETE, null);
        var reads = local.closeAndDrain(lease, generation);
        if (producers.active() != 0 || !reads.drained()) return probe(TenantReadFence.State.DRAINING, null);
        var approval = control.approval();
        return probe(TenantReadFence.State.CLOSED, RedisTenantRuntimeControl.digest(List.of("owned-runtime-v1",
                local.runtimeId().toString(), approval.approvalToken(), approval.coverageToken(), manifest,
                lease.key().scopeToken(), lease.key().tenantToken(), lease.operationId().toString(),
                lease.owner().toString(), Long.toString(lease.epoch()), Long.toString(generation))));
    }

    public RedisNativeTenantReadFence fence(ProtectionKey key, int freshnessMillis) {
        var approval = control.approval();
        if (!approval.pairs().contains(key)) throw RedisTenantRuntimeControl.incomplete();
        return new RedisNativeTenantReadFence(redis, barrier(), key,
                new RedisNativeTenantReadFence.RuntimeManifest(approval.manifestToken(), approval.members(), true),
                local, this, freshnessMillis);
    }

    /**
     * An explicit synchronous owned delivery boundary. The callback must not escape work to another
     * thread/stage/response writer. Cancellation of a caller never releases the executing ticket.
     * Ordinary repository checks alone do not attest application response serialization/delivery.
     */
    public <R> void deliver(ProtectionKey key, Supplier<R> read, Consumer<R> delivery) {
        Objects.requireNonNull(read); Objects.requireNonNull(delivery);
        control.requireSealed();
        if (!coverage.getAsBoolean() || !control.approval().pairs().contains(key)) throw RedisTenantRuntimeControl.incomplete();
        try (var ticket = local.begin(key)) {
            ticket.withReentrantReads(() -> {
                R result = ticket.read(read);
                ticket.check(); delivery.accept(result); ticket.check();
                return null;
            });
        }
    }
    private static RedisNativeTenantReadFence.Probe probe(TenantReadFence.State state, String token) {
        return new RedisNativeTenantReadFence.Probe(state, token);
    }
}

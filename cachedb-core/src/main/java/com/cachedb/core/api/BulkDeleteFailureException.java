package com.reactor.cachedb.core.api;

import com.reactor.cachedb.core.model.WriteReceipt;
import java.util.Collection;
import java.util.List;

/**
 * Partial Redis acceptance, not transaction rollback or SQL durability.
 * Do not blindly retry uncertain IDs; reconcile their version/durability first.
 * Messages and cause chains deliberately contain no business operands or payloads.
 */
public final class BulkDeleteFailureException extends RuntimeException {
    public enum Phase { PREFLIGHT, MUTATION, CLEANUP }
    private final Phase phase;
    private final List<WriteReceipt<?, ?>> accepted;
    private final List<?> rejected, uncertain, cleanupPending;
    private final int notAttempted;

    public BulkDeleteFailureException(Phase phase, Collection<? extends WriteReceipt<?, ?>> accepted,
            Collection<?> rejected, Collection<?> uncertain, Collection<?> cleanupPending, int notAttempted) {
        super("Bounded bulk delete incomplete at " + phase, null, false, true);
        this.phase = java.util.Objects.requireNonNull(phase);
        if (accepted.size() > 10_000 || rejected.size() > 10_000 || uncertain.size() > 10_000
                || cleanupPending.size() > 10_000 || notAttempted < 0 || notAttempted > 10_000)
            throw new IllegalArgumentException("Invalid bulk delete outcome bounds");
        this.accepted = List.copyOf(accepted);
        this.rejected = List.copyOf(rejected);
        this.uncertain = List.copyOf(uncertain);
        this.cleanupPending = List.copyOf(cleanupPending);
        this.notAttempted = notAttempted;
    }
    public Phase phase() { return phase; }
    public List<WriteReceipt<?, ?>> accepted() { return accepted; }
    public List<?> rejected() { return rejected; }
    public List<?> uncertain() { return uncertain; }
    public List<?> cleanupPending() { return cleanupPending; }
    public int notAttempted() { return notAttempted; }
}

package com.reactor.cachedb.jdbc;

import com.reactor.cachedb.core.protection.*;
import com.reactor.cachedb.core.protection.TenantPurgeProtocol.*;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.sql.DataSource;
import static com.reactor.cachedb.core.protection.ProtectionException.Code.*;

/** First-only administrative namespace creation. Ambiguous failure requires migration, never recreation. */
public final class JdbcNamespaceBootstrapGate {
    public record Request(String rosterKeyToken, String bindingToken, String mappingToken, String redisRunId, UUID incarnationId) {
        public Request {
            ProtectionKey.token(rosterKeyToken); ProtectionKey.token(bindingToken); ProtectionKey.token(mappingToken); Objects.requireNonNull(incarnationId);
            if (redisRunId == null || !redisRunId.matches("[0-9a-f]{40}")) fail(INVALID_REQUEST);
        }
        public String namespaceFingerprint() { return ProtectionKey.hash("initial-namespace-v1:" + rosterKeyToken + ":" + bindingToken + ":" + mappingToken); }
        @Override public String toString() { return "InitialNamespaceRequest[redacted]"; }
    }
    @FunctionalInterface public interface NativeInitializer {
        /** Actual root must compare the exact client/key-strategy/run/incarnation before atomic creation. */
        void initialize(Request committedRequest);
    }
    private final JdbcTenantPurgeGuard guard;
    private final Set<String> reviewedNamespaces;
    private final Semaphore calls = new Semaphore(1);
    public JdbcNamespaceBootstrapGate(JdbcTenantPurgeGuard guard, Set<String> reviewedNamespaceFingerprints) {
        this.guard = Objects.requireNonNull(guard);
        if (reviewedNamespaceFingerprints == null || reviewedNamespaceFingerprints.isEmpty()
                || reviewedNamespaceFingerprints.size() > 64) fail(INVALID_REQUEST);
        reviewedNamespaceFingerprints.forEach(ProtectionKey::token);
        reviewedNamespaces = Set.copyOf(reviewedNamespaceFingerprints);
    }
    public boolean belongsTo(DataSource source, JdbcTenantPurgeGuard expected) {
        return guard == expected && guard.belongsTo(source);
    }
    public void consumeOnce(InitialSourceHotWindow<?, ?> actualWindow, Generation generation, Request request, NativeInitializer initializer) {
        Objects.requireNonNull(actualWindow); Binding binding=actualWindow.binding();
        if (!CacheTenantContext.isAdministrative()) fail(ADMIN_REQUIRED);
        Objects.requireNonNull(request); Objects.requireNonNull(initializer);
        if (!actualWindow.fingerprint().equals(request.mappingToken())
                || !binding.fingerprint().equals(request.bindingToken())
                || !reviewedNamespaces.contains(request.namespaceFingerprint())
                || !binding.token().equals(generation.key().scopeToken())) fail(UNWIRED_PROTOCOL);
        if (!calls.tryAcquire()) fail(BUSY);
        try {
            guard.validateSource(binding);
            guard.consumeInitialNamespaceBootstrap(binding, generation, request);
            // SQL has COMMITTED. Never reset/delete consumption when native creation fails.
            initializer.initialize(request);
        } finally { calls.release(); }
    }
    private static void fail(ProtectionException.Code code) { throw new ProtectionException(code); }
}

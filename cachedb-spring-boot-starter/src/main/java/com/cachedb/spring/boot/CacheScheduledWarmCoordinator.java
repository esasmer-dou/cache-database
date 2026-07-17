package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.redis.RedisLeaderLease;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheHotSetReconciliationResult;
import com.reactor.cachedb.starter.CacheWarmPlan;
import com.reactor.cachedb.starter.CacheWarmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.concurrent.locks.LockSupport;

final class CacheScheduledWarmCoordinator implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheScheduledWarmCoordinator.class);
    private static final long CONCURRENT_TRIGGER_TOLERANCE_MILLIS = 1_000L;

    private final CacheDatabase cacheDatabase;
    private final JedisPooled jedis;
    private final CacheScheduledWarmRegistry registry;
    private final String instanceId;
    private final String keyPrefix;
    private final ScheduledExecutorService heartbeatExecutor;
    private final long shutdownAwaitMillis;
    private final Clock clock;

    CacheScheduledWarmCoordinator(
            CacheDatabase cacheDatabase,
            JedisPooled jedis,
            CacheScheduledWarmRegistry registry,
            String lockKeySegment,
            int heartbeatThreads,
            long shutdownAwaitMillis
    ) {
        this(
                cacheDatabase,
                jedis,
                registry,
                lockKeySegment,
                heartbeatThreads,
                shutdownAwaitMillis,
                Clock.systemUTC()
        );
    }

    CacheScheduledWarmCoordinator(
            CacheDatabase cacheDatabase,
            JedisPooled jedis,
            CacheScheduledWarmRegistry registry,
            String lockKeySegment,
            int heartbeatThreads,
            long shutdownAwaitMillis,
            Clock clock
    ) {
        this.cacheDatabase = Objects.requireNonNull(cacheDatabase, "cacheDatabase");
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.instanceId = cacheDatabase.instanceId();
        String segment = normalizeKeySegment(lockKeySegment == null || lockKeySegment.isBlank()
                ? "coordination:scheduled-warm"
                : lockKeySegment);
        this.keyPrefix = cacheDatabase.config().keyspace().keyPrefix() + ":" + segment;
        this.shutdownAwaitMillis = Math.max(0L, shutdownAwaitMillis);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heartbeatExecutor = Executors.newScheduledThreadPool(
                Math.max(1, heartbeatThreads),
                runnable -> {
                    Thread thread = new Thread(runnable, "cachedb-scheduled-warm-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    void register(String jobName) {
        registry.registered(jobName, instanceId);
    }

    void execute(CacheScheduledWarmDefinition definition, Supplier<CacheWarmPlan> planSupplier) {
        Instant triggerStartedAt = clock.instant();
        String jobPrefix = jobPrefix(definition.jobName());
        long lastCompletedAt = readEpochMillis(jobPrefix + ":completed-at");
        if (alreadyCompletedForCycle(lastCompletedAt, triggerStartedAt, definition.minimumInterval())) {
            registry.skipped(
                    definition.jobName(),
                    instanceId,
                    CacheScheduledWarmState.SKIPPED_NOT_DUE,
                    "Another instance completed this cluster-wide warm interval"
            );
            return;
        }

        RedisLeaderLease lease = RedisLeaderLease.create(
                jedis,
                jobPrefix + ":lock",
                instanceId + ":" + UUID.randomUUID(),
                definition.lockAtMostFor().toMillis(),
                renewalInterval(definition.lockAtMostFor()).toMillis()
        );
        boolean acquired = awaitLease(lease, definition.lockWaitTimeout(), definition.lockRetryInterval());
        if (!acquired) {
            lease.close();
            registry.skipped(
                    definition.jobName(),
                    instanceId,
                    CacheScheduledWarmState.SKIPPED_LOCK_TIMEOUT,
                    "Another instance still owns the warm lease after the bounded wait timeout"
            );
            return;
        }

        try (lease) {
            lastCompletedAt = readEpochMillis(jobPrefix + ":completed-at");
            if (alreadyCompletedForCycle(lastCompletedAt, triggerStartedAt, definition.minimumInterval())) {
                registry.skipped(
                        definition.jobName(),
                        instanceId,
                        CacheScheduledWarmState.SKIPPED_NOT_DUE,
                        "The lease owner completed while this instance was waiting"
                );
                return;
            }
            executeAsLeaseOwner(definition, planSupplier, jobPrefix, lease);
        }
    }

    private void executeAsLeaseOwner(
            CacheScheduledWarmDefinition definition,
            Supplier<CacheWarmPlan> planSupplier,
            String jobPrefix,
            RedisLeaderLease lease
    ) {
        Instant startedAt = clock.instant();
        registry.running(definition.jobName(), instanceId, startedAt);
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        Duration renewalInterval = renewalInterval(definition.lockAtMostFor());
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> {
                    if (!lease.tryAcquireOrRenewLeadership()) {
                        leaseLost.set(true);
                    }
                },
                renewalInterval.toMillis(),
                renewalInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );

        try {
            CacheWarmPlan plan = Objects.requireNonNull(
                    planSupplier.get(),
                    "@CacheScheduledWarm method returned null for " + definition.jobName()
            );
            verifyStableEntity(jobPrefix, plan.entityName());
            CacheWarmResult warmResult = executeWarm(definition.mode(), plan);
            CacheHotSetReconciliationResult reconciliation = definition.reconcileHotSet()
                    ? reconcile(definition, plan, jobPrefix)
                    : null;
            if (leaseLost.get() || !lease.tryAcquireOrRenewLeadership()) {
                throw new LeaseLostException("Redis warm lease was lost before completion was committed");
            }
            Instant finishedAt = clock.instant();
            jedis.set(jobPrefix + ":completed-at", String.valueOf(finishedAt.toEpochMilli()));
            registry.completed(
                    definition.jobName(),
                    instanceId,
                    startedAt,
                    finishedAt,
                    warmResult,
                    reconciliation
            );
            LOGGER.info(
                    "CacheDB scheduled warm completed job={} entity={} loaded={} submitted={} inspected={} evicted={} missing={} invalid={} fullCycle={} durationMs={}",
                    definition.jobName(),
                    plan.entityName(),
                    warmResult.loadedRows(),
                    warmResult.submittedRows(),
                    reconciliation == null ? 0 : reconciliation.inspectedRows(),
                    reconciliation == null ? 0 : reconciliation.evictedRows(),
                    reconciliation == null ? 0 : reconciliation.missingRows(),
                    reconciliation == null ? 0 : reconciliation.invalidRows(),
                    reconciliation != null && reconciliation.fullCycleCompleted(),
                    Math.max(0L, finishedAt.toEpochMilli() - startedAt.toEpochMilli())
            );
        } catch (RuntimeException failure) {
            CacheScheduledWarmState state = failure instanceof LeaseLostException
                    ? CacheScheduledWarmState.LEASE_LOST
                    : CacheScheduledWarmState.FAILED;
            registry.failed(definition.jobName(), instanceId, startedAt, state, failure);
            LOGGER.error("CacheDB scheduled warm failed job={}", definition.jobName(), failure);
        } finally {
            heartbeat.cancel(false);
        }
    }

    private CacheWarmResult executeWarm(CacheScheduledWarmMode mode, CacheWarmPlan plan) {
        return switch (mode) {
            case ENTITY_AND_PROJECTIONS -> cacheDatabase.warm(plan);
            case PROJECTIONS_ONLY -> cacheDatabase.warmProjections(plan);
            case DRY_RUN -> cacheDatabase.dryRun(plan);
        };
    }

    private CacheHotSetReconciliationResult reconcile(
            CacheScheduledWarmDefinition definition,
            CacheWarmPlan plan,
            String jobPrefix
    ) {
        if (definition.mode() != CacheScheduledWarmMode.ENTITY_AND_PROJECTIONS) {
            throw new IllegalStateException(
                    "Hot-set reconciliation requires ENTITY_AND_PROJECTIONS mode for " + definition.jobName()
            );
        }
        String cursorKey = jobPrefix + ":reconcile-cursor";
        String cursor = jedis.get(cursorKey);
        CacheHotSetReconciliationResult result = cacheDatabase.reconcileHotSet(
                plan.entityName(),
                cursor,
                definition.reconcileMaxRowsPerRun(),
                definition.reconcileScanCount()
        );
        jedis.set(cursorKey, result.nextCursor());
        return result;
    }

    private void verifyStableEntity(String jobPrefix, String entityName) {
        String entityKey = jobPrefix + ":entity";
        Long inserted = jedis.setnx(entityKey, entityName);
        if (inserted != null && inserted == 1L) {
            return;
        }
        String registeredEntity = jedis.get(entityKey);
        if (!entityName.equals(registeredEntity)) {
            throw new IllegalStateException(
                    "Scheduled warm job name is already bound to entity " + registeredEntity
                            + " and cannot be reused for " + entityName
            );
        }
    }

    private boolean awaitLease(RedisLeaderLease lease, Duration waitTimeout, Duration retryInterval) {
        long timeoutNanos = Math.max(0L, waitTimeout.toNanos());
        long deadline = System.nanoTime() + timeoutNanos;
        do {
            if (lease.tryAcquireOrRenewLeadership()) {
                return true;
            }
            if (timeoutNanos == 0L || System.nanoTime() >= deadline) {
                return false;
            }
            LockSupport.parkNanos(Math.min(retryInterval.toNanos(), Math.max(1L, deadline - System.nanoTime())));
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    private boolean alreadyCompletedForCycle(long lastCompletedAt, Instant triggerStartedAt, Duration minimumInterval) {
        if (lastCompletedAt <= 0L) {
            return false;
        }
        long triggerEpochMillis = triggerStartedAt.toEpochMilli();
        if (lastCompletedAt >= triggerEpochMillis - CONCURRENT_TRIGGER_TOLERANCE_MILLIS) {
            return true;
        }
        long minimumIntervalMillis = Math.max(0L, minimumInterval.toMillis());
        return minimumIntervalMillis > 0L && triggerEpochMillis - lastCompletedAt < minimumIntervalMillis;
    }

    private long readEpochMillis(String key) {
        String value = jedis.get(key);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("Invalid scheduled warm timestamp at Redis key " + key, failure);
        }
    }

    private Duration renewalInterval(Duration leaseDuration) {
        return Duration.ofMillis(Math.max(250L, leaseDuration.toMillis() / 3L));
    }

    private String jobPrefix(String jobName) {
        return keyPrefix + ":" + sanitize(jobName);
    }

    private String sanitize(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim();
        StringBuilder builder = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (Character.isLetterOrDigit(current) || current == '-' || current == '_' || current == '.') {
                builder.append(Character.toLowerCase(current));
            } else {
                builder.append('-');
            }
        }
        String sanitized = builder.toString().replaceAll("-{2,}", "-");
        sanitized = sanitized.replaceAll("^-+", "").replaceAll("-+$", "");
        return sanitized.isBlank() ? "default" : sanitized;
    }

    private String normalizeKeySegment(String value) {
        String[] segments = value.split(":");
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (normalized.length() > 0) {
                normalized.append(':');
            }
            normalized.append(sanitize(segment));
        }
        return normalized.toString();
    }

    @Override
    public void close() {
        heartbeatExecutor.shutdown();
        try {
            if (!heartbeatExecutor.awaitTermination(shutdownAwaitMillis, TimeUnit.MILLISECONDS)) {
                heartbeatExecutor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            heartbeatExecutor.shutdownNow();
        }
    }

    private static final class LeaseLostException extends IllegalStateException {
        private LeaseLostException(String message) {
            super(message);
        }
    }
}

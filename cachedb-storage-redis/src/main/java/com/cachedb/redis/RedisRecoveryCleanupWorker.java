package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.DeadLetterRecoveryConfig;
import com.reactor.cachedb.core.queue.RecoveryCleanupSnapshot;
import com.reactor.cachedb.core.queue.WorkerErrorCapture;
import com.reactor.cachedb.core.queue.WorkerErrorDetails;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.resps.StreamEntry;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class RedisRecoveryCleanupWorker implements AutoCloseable {

    private final JedisPooled jedis;
    private final DeadLetterRecoveryConfig config;
    private final String deadLetterStreamKey;
    private final RedisLeaderLease leaderLease;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong runCount = new AtomicLong();
    private final AtomicLong prunedDeadLetterCount = new AtomicLong();
    private final AtomicLong prunedReconciliationCount = new AtomicLong();
    private final AtomicLong prunedArchiveCount = new AtomicLong();
    private final AtomicLong lastRunAtEpochMillis = new AtomicLong();
    private final AtomicLong lastErrorAtEpochMillis = new AtomicLong();
    private final AtomicReference<String> lastErrorType = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<String> lastErrorRootType = new AtomicReference<>();
    private final AtomicReference<String> lastErrorRootMessage = new AtomicReference<>();
    private final AtomicReference<String> lastErrorOrigin = new AtomicReference<>();
    private final AtomicReference<String> lastErrorStackTrace = new AtomicReference<>();
    private Thread workerThread;

    public RedisRecoveryCleanupWorker(JedisPooled jedis, DeadLetterRecoveryConfig config, String deadLetterStreamKey) {
        this(jedis, config, deadLetterStreamKey, RedisLeaderLease.disabled());
    }

    public RedisRecoveryCleanupWorker(
            JedisPooled jedis,
            DeadLetterRecoveryConfig config,
            String deadLetterStreamKey,
            RedisLeaderLease leaderLease
    ) {
        this.jedis = jedis;
        this.config = config;
        this.deadLetterStreamKey = deadLetterStreamKey;
        this.leaderLease = leaderLease == null ? RedisLeaderLease.disabled() : leaderLease;
    }

    public void start() {
        if (!config.cleanupEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        workerThread = new Thread(this::runLoop, config.consumerNamePrefix() + "-cleanup");
        workerThread.setDaemon(config.daemonThreads());
        workerThread.start();
    }

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (!leaderLease.tryAcquireOrRenewLeadership()) {
                    sleepQuietly(config.cleanupIntervalMillis());
                    continue;
                }
                prunedDeadLetterCount.addAndGet(cleanupStream(deadLetterStreamKey, config.deadLetterRetentionMillis()));
                prunedReconciliationCount.addAndGet(cleanupStream(config.reconciliationStreamKey(), config.reconciliationRetentionMillis()));
                prunedArchiveCount.addAndGet(cleanupStream(config.archiveStreamKey(), config.archiveRetentionMillis()));
                runCount.incrementAndGet();
                lastRunAtEpochMillis.set(System.currentTimeMillis());
                Thread.sleep(config.cleanupIntervalMillis());
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException runtimeException) {
                if (isTransientRedisTimeout(runtimeException)) {
                    sleepQuietly(config.cleanupIntervalMillis());
                    continue;
                }
                captureError(runtimeException);
                sleepQuietly(config.cleanupIntervalMillis());
            }
        }
    }

    private long cleanupStream(String streamKey, long retentionMillis) {
        if (retentionMillis <= 0) {
            return 0L;
        }
        long cutoffEpochMillis = System.currentTimeMillis() - retentionMillis;
        String maxId = cutoffEpochMillis + "-999999";
        long deleted = 0L;

        while (running.get()) {
            List<StreamEntry> expiredEntries = jedis.xrange(streamKey, "-", maxId, config.cleanupBatchSize());
            if (expiredEntries == null || expiredEntries.isEmpty()) {
                return deleted;
            }
            StreamEntryID[] ids = expiredEntries.stream()
                    .map(StreamEntry::getID)
                    .toArray(StreamEntryID[]::new);
            deleted += jedis.xdel(streamKey, ids);
            if (expiredEntries.size() < config.cleanupBatchSize()) {
                return deleted;
            }
        }
        return deleted;
    }

    private void captureError(RuntimeException runtimeException) {
        WorkerErrorDetails details = WorkerErrorCapture.capture(runtimeException);
        lastErrorAtEpochMillis.set(System.currentTimeMillis());
        lastErrorType.set(details.errorType());
        lastErrorMessage.set(details.errorMessage());
        lastErrorRootType.set(details.rootErrorType());
        lastErrorRootMessage.set(details.rootErrorMessage());
        lastErrorOrigin.set(details.origin());
        lastErrorStackTrace.set(details.stackTrace());
    }

    private void sleepQuietly(long sleepMillis) {
        if (sleepMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isTransientRedisTimeout(RuntimeException exception) {
        return RedisTransientConnectionFailures.isTransient(exception);
    }

    @Override
    public void close() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(config.shutdownAwaitMillis());
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        leaderLease.close();
    }

    public RecoveryCleanupSnapshot snapshot() {
        return new RecoveryCleanupSnapshot(
                runCount.get(),
                prunedDeadLetterCount.get(),
                prunedReconciliationCount.get(),
                prunedArchiveCount.get(),
                lastRunAtEpochMillis.get(),
                lastErrorAtEpochMillis.get(),
                lastErrorType.get(),
                lastErrorMessage.get(),
                lastErrorRootType.get(),
                lastErrorRootMessage.get(),
                lastErrorOrigin.get(),
                lastErrorStackTrace.get()
        );
    }
}

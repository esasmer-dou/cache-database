package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.WriteBehindConfig;
import com.reactor.cachedb.core.config.WriteRetryPolicy;
import com.reactor.cachedb.core.queue.FailureClassifyingFlusher;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;
import com.reactor.cachedb.core.queue.WorkerErrorCapture;
import com.reactor.cachedb.core.queue.WorkerErrorDetails;
import com.reactor.cachedb.core.queue.WriteFailureDetails;
import com.reactor.cachedb.core.queue.WriteBehindWorkerSnapshot;
import com.reactor.cachedb.core.queue.WriteBehindFlusher;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class RedisWriteBehindWorker implements AutoCloseable {

    private final JedisPooled jedis;
    private final WriteBehindFlusher flusher;
    private final WriteBehindConfig config;
    private final RedisWriteOperationMapper mapper;
    private final RedisKeyStrategy keyStrategy;
    private final RedisFunctionExecutor functionExecutor;
    private final ExecutorService executorService;
    private final ExecutorService flushExecutorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong flushedCount = new AtomicLong();
    private final AtomicLong batchFlushCount = new AtomicLong();
    private final AtomicLong batchFlushedOperationCount = new AtomicLong();
    private final AtomicLong lastObservedBacklog = new AtomicLong();
    private final AtomicInteger lastAdaptiveBatchSize = new AtomicInteger();
    private final AtomicLong coalescedCount = new AtomicLong();
    private final AtomicInteger lastInFlightFlushGroups = new AtomicInteger();
    private final AtomicLong staleSkippedCount = new AtomicLong();
    private final AtomicLong retriedCount = new AtomicLong();
    private final AtomicLong deadLetterCount = new AtomicLong();
    private final AtomicLong claimedCount = new AtomicLong();
    private final AtomicLong pendingRecoveryCount = new AtomicLong();
    private final AtomicLong lastClaimAttemptAtEpochMillis = new AtomicLong();
    private final AtomicLong lastProgressAtEpochMillis = new AtomicLong();
    private final AtomicLong lastBacklogSampleAtEpochMillis = new AtomicLong();
    private final AtomicLong cachedBacklogLength = new AtomicLong();
    private final AtomicLong lastErrorAtEpochMillis = new AtomicLong();
    private final AtomicReference<String> lastErrorType = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<String> lastErrorRootType = new AtomicReference<>();
    private final AtomicReference<String> lastErrorRootMessage = new AtomicReference<>();
    private final AtomicReference<String> lastErrorOrigin = new AtomicReference<>();
    private final AtomicReference<String> lastErrorStackTrace = new AtomicReference<>();
    private final ConcurrentLinkedQueue<PendingEntry> deferredAcknowledgements = new ConcurrentLinkedQueue<>();

    public RedisWriteBehindWorker(
            JedisPooled jedis,
            WriteBehindFlusher flusher,
            WriteBehindConfig config,
            RedisWriteOperationMapper mapper,
            RedisKeyStrategy keyStrategy,
            RedisFunctionExecutor functionExecutor
    ) {
        this.jedis = jedis;
        this.flusher = flusher;
        this.config = config;
        this.mapper = mapper;
        this.keyStrategy = keyStrategy;
        this.functionExecutor = functionExecutor;
        this.executorService = Executors.newFixedThreadPool(config.workerThreads(), threadFactory(config));
        this.flushExecutorService = Executors.newFixedThreadPool(
                Math.max(1, config.flushGroupParallelism()),
                flushThreadFactory(config)
        );
    }

    public RedisWriteBehindWorker(
            JedisPooled jedis,
            WriteBehindFlusher flusher,
            WriteBehindConfig config,
            RedisWriteOperationMapper mapper,
            RedisKeyStrategy keyStrategy
    ) {
        this(jedis, flusher, config, mapper, keyStrategy, null);
    }

    public void start() {
        if (!config.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        ensureConsumerGroup();
        for (int i = 0; i < config.workerThreads(); i++) {
            String consumerName = config.activeConsumerNamePrefix() + "-" + i;
            executorService.submit(() -> runLoop(consumerName));
        }
    }

    private void ensureConsumerGroup() {
        if (!config.autoCreateConsumerGroup()) {
            return;
        }

        for (String streamKey : config.activeStreamKeys()) {
            try {
                jedis.xgroupCreate(streamKey, config.activeConsumerGroup(), StreamEntryID.LAST_ENTRY, true);
            } catch (JedisDataException exception) {
                if (!exception.getMessage().contains("BUSYGROUP")) {
                    throw exception;
                }
            }
        }
    }

    private void runLoop(String consumerName) {
        XReadGroupParams params = new XReadGroupParams()
                .count(config.batchSize())
                .block((int) config.blockTimeoutMillis());
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                drainDeferredAcknowledgements();
                long now = System.currentTimeMillis();
                List<Entry<String, List<StreamEntry>>> responses = List.of();
                boolean claimedRecoveryEntries = false;
                if (!hasEntries(responses)) {
                    responses = readNewEntries(consumerName, params);
                }
                if (hasEntries(responses)) {
                    markProgress(now);
                }
                if (!hasEntries(responses) && config.recoverPendingEntries() && shouldAttemptClaim(now)) {
                    try {
                        responses = claimAbandonedEntries(consumerName);
                        claimedRecoveryEntries = hasEntries(responses);
                    } catch (RuntimeException exception) {
                        if (isTransientRedisTimeout(exception)) {
                            sleepQuietly(Math.max(config.retryBackoffMillis(), config.claimIdleMillis()));
                            continue;
                        }
                        throw exception;
                    }
                }

                if (!hasEntries(responses)) {
                    sleepQuietly(config.idleSleepMillis());
                    continue;
                }

                if (claimedRecoveryEntries) {
                    markProgress(now);
                }

                long currentBacklog = backlogLength(now);
                lastObservedBacklog.set(currentBacklog);
                for (Entry<String, List<StreamEntry>> response : responses) {
                    processEntries(response.getKey(), response.getValue(), currentBacklog);
                }
            } catch (RuntimeException exception) {
                captureError(exception);
                sleepQuietly(config.retryBackoffMillis());
            }
        }
    }

    private void processEntries(String sourceStreamKey, List<StreamEntry> entries, long currentBacklog) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        List<PendingEntry> candidates = config.dedicatedWriteConsumerGroupEnabled()
                ? resolveCompactionEntries(sourceStreamKey, entries)
                : rawEntries(sourceStreamKey, entries);

        if (!config.batchFlushEnabled() || candidates.size() == 1) {
            for (PendingEntry pendingEntry : candidates) {
                processOperationWithRetry(pendingEntry);
            }
            return;
        }

        if (config.coalescingEnabled()) {
            candidates = coalesceEntries(candidates);
        }

        if (config.batchStaleCheckEnabled()) {
            candidates = filterFreshEntries(candidates);
        }

        if (candidates.isEmpty()) {
            return;
        }

        LinkedHashMap<FlushGroupKey, List<PendingEntry>> groups = groupEntries(candidates);
        int maxInFlightGroups = Math.max(1, Math.min(config.flushGroupParallelism(), config.flushPipelineDepth()));
        if (groups.size() <= 1 || maxInFlightGroups <= 1) {
            for (List<PendingEntry> group : groups.values()) {
                flushGroup(group, currentBacklog);
            }
            lastInFlightFlushGroups.set(1);
            return;
        }

        ArrayList<Future<?>> futures = new ArrayList<>(groups.size());
        for (List<PendingEntry> group : groups.values()) {
            futures.add(flushExecutorService.submit(() -> flushGroup(group, currentBacklog)));
            lastInFlightFlushGroups.set(Math.max(lastInFlightFlushGroups.get(), futures.size()));
            if (futures.size() >= maxInFlightGroups) {
                waitForFutures(futures);
                futures.clear();
            }
        }
        waitForFutures(futures);
    }

    private List<PendingEntry> rawEntries(String sourceStreamKey, List<StreamEntry> entries) {
        List<PendingEntry> candidates = new ArrayList<>(entries.size());
        for (StreamEntry entry : entries) {
            candidates.add(new PendingEntry(entry, mapper.fromBody(entry.getFields()), List.of(entry.getID()), false, sourceStreamKey));
        }
        return candidates;
    }

    private List<PendingEntry> resolveCompactionEntries(String sourceStreamKey, List<StreamEntry> entries) {
        LinkedHashMap<EntityKey, CompactionTokenGroup> tokenGroups = new LinkedHashMap<>();
        for (StreamEntry entry : entries) {
            String namespace = entry.getFields().get("namespace");
            String id = entry.getFields().get("id");
            if (namespace == null || id == null) {
                ackToken(sourceStreamKey, entry.getID());
                continue;
            }
            tokenGroups.compute(
                    EntityKey.of(namespace, id),
                    (ignored, currentGroup) -> currentGroup == null
                            ? CompactionTokenGroup.initial(entry, namespace, id)
                            : currentGroup.merge(entry)
            );
        }
        if (tokenGroups.isEmpty()) {
            return List.of();
        }

        ArrayList<CompactionTokenGroup> orderedGroups = new ArrayList<>(tokenGroups.values());
        ArrayList<PendingEntry> candidates = new ArrayList<>(orderedGroups.size());
        int chunkSize = compactionPayloadFetchChunkSize(orderedGroups.size());
        for (int start = 0; start < orderedGroups.size(); start += chunkSize) {
            int end = Math.min(orderedGroups.size(), start + chunkSize);
            candidates.addAll(resolveCompactionChunk(sourceStreamKey, orderedGroups.subList(start, end)));
        }
        return candidates;
    }

    private List<PendingEntry> resolveCompactionChunk(String sourceStreamKey, List<CompactionTokenGroup> tokenGroups) {
        LinkedHashMap<EntityKey, Response<Map<String, String>>> payloadResponses = new LinkedHashMap<>(tokenGroups.size());
        Pipeline pipeline = jedis.pipelined();
        try {
            for (CompactionTokenGroup tokenGroup : tokenGroups) {
                payloadResponses.put(
                        EntityKey.of(tokenGroup.namespace(), tokenGroup.id()),
                        pipeline.hgetAll(keyStrategy.compactionPayloadKey(tokenGroup.namespace(), tokenGroup.id()))
                );
            }
            pipeline.sync();
        } catch (RuntimeException exception) {
            if (!isTransientRedisTimeout(exception)) {
                throw exception;
            }
            return resolveCompactionChunkSequential(sourceStreamKey, tokenGroups);
        } finally {
            pipeline.close();
        }

        ArrayList<PendingEntry> candidates = new ArrayList<>(tokenGroups.size());
        for (CompactionTokenGroup tokenGroup : tokenGroups) {
            Map<String, String> body = payloadResponses.get(EntityKey.of(tokenGroup.namespace(), tokenGroup.id())).get();
            PendingEntry pendingEntry = resolveCompactionEntry(sourceStreamKey, tokenGroup, body);
            if (pendingEntry != null) {
                candidates.add(pendingEntry);
            }
        }
        return candidates;
    }

    private List<PendingEntry> resolveCompactionChunkSequential(String sourceStreamKey, List<CompactionTokenGroup> tokenGroups) {
        ArrayList<PendingEntry> candidates = new ArrayList<>(tokenGroups.size());
        for (CompactionTokenGroup tokenGroup : tokenGroups) {
            Map<String, String> body;
            try {
                body = jedis.hgetAll(keyStrategy.compactionPayloadKey(tokenGroup.namespace(), tokenGroup.id()));
            } catch (RuntimeException exception) {
                if (isTransientRedisTimeout(exception)) {
                    continue;
                }
                throw exception;
            }
            PendingEntry pendingEntry = resolveCompactionEntry(sourceStreamKey, tokenGroup, body);
            if (pendingEntry != null) {
                candidates.add(pendingEntry);
            }
        }
        return candidates;
    }

    private PendingEntry resolveCompactionEntry(String sourceStreamKey, CompactionTokenGroup tokenGroup, Map<String, String> body) {
        if (body == null || body.isEmpty()) {
            for (StreamEntryID ackId : tokenGroup.ackIds()) {
                ackToken(sourceStreamKey, ackId);
            }
            long pendingRemoved = jedis.del(keyStrategy.compactionPendingKey(tokenGroup.namespace(), tokenGroup.id()));
            if (pendingRemoved > 0) {
                jedis.hincrBy(keyStrategy.compactionStatsKey(), "pendingCount", -pendingRemoved);
            }
            return null;
        }
        return new PendingEntry(
                tokenGroup.entry(),
                mapper.fromBody(body),
                tokenGroup.ackIds(),
                true,
                sourceStreamKey
        );
    }

    private int compactionPayloadFetchChunkSize(int entryCount) {
        return Math.max(16, Math.min(entryCount, Math.min(config.batchSize(), 64)));
    }

    private long backlogLength(long now) {
        long lastSampleAt = lastBacklogSampleAtEpochMillis.get();
        if (lastSampleAt > 0 && (now - lastSampleAt) < 500L) {
            return cachedBacklogLength.get();
        }
        long total = 0L;
        for (String streamKey : config.activeStreamKeys()) {
            total += jedis.xlen(streamKey);
        }
        cachedBacklogLength.set(total);
        lastBacklogSampleAtEpochMillis.set(now);
        return total;
    }

    private void waitForFutures(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception exception) {
                captureError(exception instanceof RuntimeException runtimeException ? runtimeException : new RuntimeException(exception));
            }
        }
    }

    private LinkedHashMap<FlushGroupKey, List<PendingEntry>> groupEntries(List<PendingEntry> entries) {
        if (!config.tableAwareBatchingEnabled()) {
            LinkedHashMap<FlushGroupKey, List<PendingEntry>> singleGroup = new LinkedHashMap<>();
            singleGroup.put(FlushGroupKey.singleGroup(), entries);
            return singleGroup;
        }

        LinkedHashMap<FlushGroupKey, List<PendingEntry>> groups = new LinkedHashMap<>();
        for (PendingEntry entry : entries) {
            groups.computeIfAbsent(FlushGroupKey.of(entry.operation()), ignored -> new ArrayList<>()).add(entry);
        }
        return groups;
    }

    private List<PendingEntry> coalesceEntries(List<PendingEntry> entries) {
        LinkedHashMap<EntityKey, PendingEntry> latestEntries = new LinkedHashMap<>();
        for (PendingEntry entry : entries) {
            EntityKey key = EntityKey.of(entry.operation());
            PendingEntry current = latestEntries.get(key);
            if (current == null) {
                latestEntries.put(key, entry);
                continue;
            }
            latestEntries.put(key, current.merge(entry));
            coalescedCount.incrementAndGet();
        }
        return new ArrayList<>(latestEntries.values());
    }

    private void flushGroup(List<PendingEntry> entries, long currentBacklog) {
        int batchSize = resolveAdaptiveBatchSize(currentBacklog);
        lastAdaptiveBatchSize.set(batchSize);
        for (int start = 0; start < entries.size(); start += batchSize) {
            int end = Math.min(entries.size(), start + batchSize);
            List<PendingEntry> chunk = entries.subList(start, end);
            if (!flushChunk(chunk)) {
                for (PendingEntry pendingEntry : chunk) {
                    processOperationWithRetry(pendingEntry);
                }
            }
        }
    }

    private boolean flushChunk(List<PendingEntry> chunk) {
        if (chunk.size() == 1) {
            processOperationWithRetry(chunk.get(0));
            return true;
        }

        List<QueuedWriteOperation> operations = chunk.stream().map(PendingEntry::operation).toList();
        try {
            flusher.flushBatch(operations);
            acknowledgeOrDefer(chunk);
            batchFlushCount.incrementAndGet();
            batchFlushedOperationCount.addAndGet(chunk.size());
            flushedCount.addAndGet(chunk.size());
            return true;
        } catch (SQLException | RuntimeException exception) {
            captureError(exception);
            return false;
        }
    }

    private List<PendingEntry> filterFreshEntries(List<PendingEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        int chunkSize = staleCheckChunkSize(entries.size());
        ArrayList<PendingEntry> freshEntries = new ArrayList<>(entries.size());
        for (int start = 0; start < entries.size(); start += chunkSize) {
            int end = Math.min(entries.size(), start + chunkSize);
            List<PendingEntry> chunk = entries.subList(start, end);
            try {
                freshEntries.addAll(filterFreshEntriesChunk(chunk));
            } catch (RuntimeException exception) {
                if (!isTransientRedisTimeout(exception)) {
                    throw exception;
                }
                // PostgreSQL write-behind already guards rows by version, so a transient
                // Redis timeout here should not poison the worker or block the flush batch.
                freshEntries.addAll(chunk);
            }
        }
        return freshEntries;
    }

    private List<PendingEntry> filterFreshEntriesChunk(List<PendingEntry> entries) {
        List<String> versionKeys = entries.stream()
                .map(entry -> keyStrategy.versionKey(entry.operation().redisNamespace(), entry.operation().id()))
                .toList();
        List<String> tombstoneKeys = entries.stream()
                .map(entry -> keyStrategy.tombstoneKey(entry.operation().redisNamespace(), entry.operation().id()))
                .toList();
        List<String> currentVersions = jedis.mget(versionKeys.toArray(String[]::new));
        List<String> tombstoneVersions = jedis.mget(tombstoneKeys.toArray(String[]::new));
        ArrayList<PendingEntry> freshEntries = new ArrayList<>(entries.size());
        ArrayList<PendingEntry> staleEntries = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            PendingEntry pendingEntry = entries.get(index);
            long latestVersion = Math.max(parseVersion(currentVersions.get(index)), parseVersion(tombstoneVersions.get(index)));
            if (latestVersion > pendingEntry.operation().version()) {
                staleEntries.add(pendingEntry);
                staleSkippedCount.incrementAndGet();
                continue;
            }
            freshEntries.add(pendingEntry);
        }
        ackPendingEntries(staleEntries);
        return freshEntries;
    }

    private int staleCheckChunkSize(int entryCount) {
        return Math.max(32, Math.min(entryCount, Math.min(config.batchSize(), 64)));
    }

    private int resolveAdaptiveBatchSize(long currentBacklog) {
        if (currentBacklog >= config.adaptiveBacklogCriticalWatermark()) {
            return Math.max(1, config.adaptiveCriticalFlushBatchSize());
        }
        if (currentBacklog >= config.adaptiveBacklogHighWatermark()) {
            return Math.max(1, config.adaptiveHighFlushBatchSize());
        }
        return Math.max(1, config.maxFlushBatchSize());
    }

    private void processEntry(String sourceStreamKey, StreamEntry entry) {
        processOperationWithRetry(new PendingEntry(
                entry,
                mapper.fromBody(entry.getFields()),
                List.of(entry.getID()),
                false,
                sourceStreamKey
        ));
    }

    private void processOperationWithRetry(PendingEntry pendingEntry) {
        WriteRetryPolicy retryPolicy = resolveRetryPolicy(pendingEntry.operation());
        if (!config.batchStaleCheckEnabled() && isStale(pendingEntry.operation())) {
            acknowledgeOrDefer(pendingEntry);
            staleSkippedCount.incrementAndGet();
            return;
        }
        int attempts = 0;
        while (attempts <= retryPolicy.maxRetries()) {
            try {
                flusher.flush(pendingEntry.operation());
                acknowledgeOrDefer(pendingEntry);
                flushedCount.incrementAndGet();
                return;
            } catch (SQLException | RuntimeException exception) {
                captureError(exception);
                attempts++;
                retriedCount.incrementAndGet();
                if (attempts > retryPolicy.maxRetries()) {
                    publishDeadLetter(pendingEntry.entry(), pendingEntry.operation(), exception, attempts);
                    acknowledgeOrDefer(pendingEntry);
                    return;
                }
                sleepQuietly(retryPolicy.backoffMillis());
            }
        }
    }

    private void acknowledgeOrDefer(PendingEntry pendingEntry) {
        try {
            ackPendingEntry(pendingEntry);
        } catch (RuntimeException exception) {
            deferAcknowledgement(pendingEntry, exception);
        }
    }

    private void acknowledgeOrDefer(List<PendingEntry> pendingEntries) {
        if (pendingEntries == null || pendingEntries.isEmpty()) {
            return;
        }
        try {
            ackPendingEntries(pendingEntries);
        } catch (RuntimeException exception) {
            deferAcknowledgements(pendingEntries, exception);
        }
    }

    private void ackPendingEntry(PendingEntry pendingEntry) {
        if (pendingEntry.ackIds().isEmpty()) {
            return;
        }
        if (pendingEntry.compacted()) {
            completeCompaction(pendingEntry.operation());
        }
        jedis.xack(pendingEntry.sourceStreamKey(), config.activeConsumerGroup(), pendingEntry.ackIds().toArray(StreamEntryID[]::new));
    }

    private void ackPendingEntries(List<PendingEntry> pendingEntries) {
        if (pendingEntries == null || pendingEntries.isEmpty()) {
            return;
        }
        LinkedHashMap<String, ArrayList<StreamEntryID>> ackIdsByStream = new LinkedHashMap<>();
        for (PendingEntry pendingEntry : pendingEntries) {
            if (pendingEntry.ackIds().isEmpty()) {
                continue;
            }
            if (pendingEntry.compacted()) {
                completeCompaction(pendingEntry.operation());
            }
            ackIdsByStream
                    .computeIfAbsent(pendingEntry.sourceStreamKey(), ignored -> new ArrayList<>())
                    .addAll(pendingEntry.ackIds());
        }
        for (Entry<String, ArrayList<StreamEntryID>> entry : ackIdsByStream.entrySet()) {
            jedis.xack(entry.getKey(), config.activeConsumerGroup(), entry.getValue().toArray(StreamEntryID[]::new));
        }
    }

    private void ackToken(String sourceStreamKey, StreamEntryID streamEntryID) {
        jedis.xack(sourceStreamKey, config.activeConsumerGroup(), streamEntryID);
    }

    private void completeCompaction(QueuedWriteOperation operation) {
        String pendingKey = keyStrategy.compactionPendingKey(operation.redisNamespace(), operation.id());
        String payloadKey = keyStrategy.compactionPayloadKey(operation.redisNamespace(), operation.id());
        if (functionExecutor != null && functionExecutor.enabled()) {
            try {
                functionExecutor.compactionComplete(
                        pendingKey,
                        payloadKey,
                        keyStrategy.compactionStreamKey(
                                config.compactionStreamKey(),
                                operation.redisNamespace(),
                                operation.id(),
                                config.compactionShardCount()
                        ),
                        keyStrategy.compactionStatsKey(),
                        operation.redisNamespace(),
                        operation.id(),
                        operation.version()
                );
                return;
            } catch (RuntimeException exception) {
                handleCompactionCompletionFailure(operation, exception);
                return;
            }
        }
        try {
            completeCompactionLocally(operation, pendingKey, payloadKey);
        } catch (RuntimeException exception) {
            handleCompactionCompletionFailure(operation, exception);
        }
    }

    private void handleCompactionCompletionFailure(QueuedWriteOperation operation, RuntimeException exception) {
        if (tryRequeueCompaction(operation, exception)) {
            return;
        }
        throw exception;
    }

    private void completeCompactionLocally(QueuedWriteOperation operation, String pendingKey, String payloadKey) {
        String pendingVersion = jedis.get(pendingKey);
        if (pendingVersion == null) {
            long payloadRemoved = jedis.del(payloadKey);
            if (payloadRemoved > 0) {
                jedis.hincrBy(keyStrategy.compactionStatsKey(), "payloadCount", -payloadRemoved);
            }
            return;
        }
        long currentVersion = Long.parseLong(pendingVersion);
        if (currentVersion <= operation.version()) {
            long pendingRemoved = jedis.del(pendingKey);
            long payloadRemoved = jedis.del(payloadKey);
            if (pendingRemoved > 0) {
                jedis.hincrBy(keyStrategy.compactionStatsKey(), "pendingCount", -pendingRemoved);
            }
            if (payloadRemoved > 0) {
                jedis.hincrBy(keyStrategy.compactionStatsKey(), "payloadCount", -payloadRemoved);
            }
            return;
        }
        String targetCompactionStreamKey = keyStrategy.compactionStreamKey(
                config.compactionStreamKey(),
                operation.redisNamespace(),
                operation.id(),
                config.compactionShardCount()
        );
        jedis.xadd(targetCompactionStreamKey, compactionAddParams(), Map.of(
                "namespace", operation.redisNamespace(),
                "id", operation.id(),
                "version", String.valueOf(currentVersion),
                "entity", operation.entityName()
        ));
    }

    private void requeueCompaction(QueuedWriteOperation operation) {
        String targetCompactionStreamKey = keyStrategy.compactionStreamKey(
                config.compactionStreamKey(),
                operation.redisNamespace(),
                operation.id(),
                config.compactionShardCount()
        );
        jedis.xadd(targetCompactionStreamKey, compactionAddParams(), Map.of(
                "namespace", operation.redisNamespace(),
                "id", operation.id(),
                "version", String.valueOf(operation.version()),
                "entity", operation.entityName()
        ));
        retriedCount.incrementAndGet();
    }

    private boolean tryRequeueCompaction(QueuedWriteOperation operation, RuntimeException cause) {
        try {
            requeueCompaction(operation);
            return true;
        } catch (RuntimeException requeueFailure) {
            if (isTransientRedisTimeout(cause) || isTransientRedisTimeout(requeueFailure)) {
                return false;
            }
            captureError(requeueFailure);
            return false;
        }
    }

    private redis.clients.jedis.params.XAddParams compactionAddParams() {
        redis.clients.jedis.params.XAddParams params = redis.clients.jedis.params.XAddParams.xAddParams();
        if (config.compactionMaxLength() > 0) {
            params.maxLen(config.compactionMaxLength()).approximateTrimming();
        }
        return params;
    }

    private void deferAcknowledgement(PendingEntry pendingEntry, RuntimeException exception) {
        deferAcknowledgements(List.of(pendingEntry), exception);
    }

    private void deferAcknowledgements(List<PendingEntry> pendingEntries, RuntimeException exception) {
        if (!isTransientRedisTimeout(exception)) {
            captureError(exception);
        }
        for (PendingEntry pendingEntry : pendingEntries) {
            deferredAcknowledgements.offer(pendingEntry);
        }
    }

    private void drainDeferredAcknowledgements() {
        int budget = Math.max(1, config.batchSize());
        for (int processed = 0; processed < budget; processed++) {
            PendingEntry pendingEntry = deferredAcknowledgements.poll();
            if (pendingEntry == null) {
                return;
            }
            try {
                ackPendingEntry(pendingEntry);
            } catch (RuntimeException exception) {
                deferredAcknowledgements.offer(pendingEntry);
                if (!isTransientRedisTimeout(exception)) {
                    captureError(exception);
                }
                sleepQuietly(Math.max(50L, config.retryBackoffMillis()));
                return;
            }
        }
    }

    private WriteRetryPolicy resolveRetryPolicy(QueuedWriteOperation operation) {
        return RedisRetryPolicyResolver.resolve(
                config.maxFlushRetries(),
                config.retryBackoffMillis(),
                config.retryOverrides(),
                operation
        );
    }

    private void captureError(Exception exception) {
        WorkerErrorDetails details = WorkerErrorCapture.capture(exception);
        lastErrorAtEpochMillis.set(System.currentTimeMillis());
        lastErrorType.set(details.errorType());
        lastErrorMessage.set(details.errorMessage());
        lastErrorRootType.set(details.rootErrorType());
        lastErrorRootMessage.set(details.rootErrorMessage());
        lastErrorOrigin.set(details.origin());
        lastErrorStackTrace.set(details.stackTrace());
    }

    private boolean isStale(QueuedWriteOperation operation) {
        long currentVersion = parseVersion(jedis.get(keyStrategy.versionKey(operation.redisNamespace(), operation.id())));
        long tombstoneVersion = parseVersion(jedis.get(keyStrategy.tombstoneKey(operation.redisNamespace(), operation.id())));
        return Math.max(currentVersion, tombstoneVersion) > operation.version();
    }

    private long parseVersion(String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(rawVersion);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private List<Entry<String, List<StreamEntry>>> claimAbandonedEntries(String consumerName) {
        lastClaimAttemptAtEpochMillis.set(System.currentTimeMillis());
        ArrayList<Entry<String, List<StreamEntry>>> responses = new ArrayList<>();
        for (String streamKey : config.activeStreamKeys()) {
            Entry<StreamEntryID, List<StreamEntry>> claimed;
            try {
                claimed = jedis.xautoclaim(
                        streamKey,
                        config.activeConsumerGroup(),
                        consumerName,
                        config.claimIdleMillis(),
                        new StreamEntryID("0-0"),
                        new XAutoClaimParams().count(config.claimBatchSize())
                );
            } catch (JedisDataException exception) {
                if (isMissingConsumerGroup(exception)) {
                    ensureConsumerGroup();
                    return List.of();
                }
                throw exception;
            }
            List<StreamEntry> claimedEntries = claimed == null ? List.of() : claimed.getValue();
            responses.add(Map.entry(streamKey, claimedEntries));
            if (!claimedEntries.isEmpty()) {
                claimedCount.addAndGet(claimedEntries.size());
                pendingRecoveryCount.incrementAndGet();
            }
        }
        return responses;
    }

    private boolean shouldAttemptClaim(long now) {
        long lastAttemptAt = lastClaimAttemptAtEpochMillis.get();
        long lastProgressAt = lastProgressAtEpochMillis.get();
        if (lastProgressAt > 0L && (now - lastProgressAt) < config.claimIdleMillis()) {
            return false;
        }
        if (lastAttemptAt == 0L) {
            return true;
        }
        long minimumInterval = Math.max(config.claimIdleMillis(), 30_000L);
        return (now - lastAttemptAt) >= minimumInterval;
    }

    private void markProgress(long now) {
        lastProgressAtEpochMillis.set(now);
    }

    private List<Entry<String, List<StreamEntry>>> readNewEntries(String consumerName, XReadGroupParams params) {
        LinkedHashMap<String, StreamEntryID> streams = new LinkedHashMap<>();
        for (String streamKey : config.activeStreamKeys()) {
            streams.put(streamKey, StreamEntryID.UNRECEIVED_ENTRY);
        }
        try {
            return jedis.xreadGroup(
                    config.activeConsumerGroup(),
                    consumerName,
                    params,
                    streams
            );
        } catch (JedisDataException exception) {
            if (isMissingConsumerGroup(exception)) {
                ensureConsumerGroup();
                return List.of();
            }
            throw exception;
        }
    }

    private boolean isMissingConsumerGroup(JedisDataException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("NOGROUP");
    }

    private boolean isTransientRedisTimeout(RuntimeException exception) {
        return RedisTransientConnectionFailures.isTransient(exception);
    }

    private boolean hasEntries(List<Entry<String, List<StreamEntry>>> responses) {
        if (responses == null || responses.isEmpty()) {
            return false;
        }
        for (Entry<String, List<StreamEntry>> response : responses) {
            if (response.getValue() != null && !response.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void publishDeadLetter(StreamEntry entry, QueuedWriteOperation operation, Exception exception, int attempts) {
        Map<String, String> fields = new java.util.LinkedHashMap<>(mapper.toBody(operation));
        for (Map.Entry<String, String> entryField : entry.getFields().entrySet()) {
            fields.putIfAbsent(entryField.getKey(), entryField.getValue());
        }
        WriteFailureDetails failureDetails = FailureClassifyingFlusher.classify(flusher, exception);
        fields.put("deadLetterReason", exception.getClass().getName());
        fields.put("deadLetterMessage", exception.getMessage() == null ? "" : exception.getMessage());
        fields.put("deadLetterFailureCategory", failureDetails.category().name());
        fields.put("deadLetterSqlState", failureDetails.sqlState());
        fields.put("deadLetterRetryable", String.valueOf(failureDetails.retryable()));
        fields.put("deadLetterVendorCode", String.valueOf(failureDetails.vendorCode()));
        fields.put("deadLetterRootType", failureDetails.errorType());
        fields.put("deadLetterAttempts", String.valueOf(attempts));
        fields.put("deadLetterEntity", operation.entityName());
        jedis.xadd(config.deadLetterStreamKey(), redis.clients.jedis.params.XAddParams.xAddParams(), fields);
        if (config.deadLetterMaxLength() > 0) {
            jedis.xtrim(config.deadLetterStreamKey(), config.deadLetterMaxLength(), true);
        }
        deadLetterCount.incrementAndGet();
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

    private ThreadFactory threadFactory(WriteBehindConfig config) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, config.activeConsumerNamePrefix() + "-thread-" + counter.incrementAndGet());
            thread.setDaemon(config.daemonThreads());
            return thread;
        };
    }

    private ThreadFactory flushThreadFactory(WriteBehindConfig config) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, config.activeConsumerNamePrefix() + "-flush-" + counter.incrementAndGet());
            thread.setDaemon(config.daemonThreads());
            return thread;
        };
    }

    @Override
    public void close() {
        running.set(false);
        executorService.shutdownNow();
        flushExecutorService.shutdownNow();
        try {
            executorService.awaitTermination(config.shutdownAwaitMillis(), TimeUnit.MILLISECONDS);
            flushExecutorService.awaitTermination(config.shutdownAwaitMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public WriteBehindWorkerSnapshot snapshot() {
        return new WriteBehindWorkerSnapshot(
                flushedCount.get(),
                batchFlushCount.get(),
                batchFlushedOperationCount.get(),
                lastObservedBacklog.get(),
                lastAdaptiveBatchSize.get(),
                coalescedCount.get(),
                lastInFlightFlushGroups.get(),
                staleSkippedCount.get(),
                retriedCount.get(),
                deadLetterCount.get(),
                claimedCount.get(),
                pendingRecoveryCount.get(),
                lastErrorAtEpochMillis.get(),
                lastErrorType.get(),
                lastErrorMessage.get(),
                lastErrorRootType.get(),
                lastErrorRootMessage.get(),
                lastErrorOrigin.get(),
                lastErrorStackTrace.get()
        );
    }

    private record PendingEntry(
            StreamEntry entry,
            QueuedWriteOperation operation,
            List<StreamEntryID> ackIds,
            boolean compacted,
            String sourceStreamKey
    ) {
        private PendingEntry merge(PendingEntry newerEntry) {
            ArrayList<StreamEntryID> mergedAckIds = new ArrayList<>(ackIds.size() + newerEntry.ackIds().size());
            mergedAckIds.addAll(ackIds);
            mergedAckIds.addAll(newerEntry.ackIds());
            if (newerEntry.operation().version() >= operation().version()) {
                return new PendingEntry(
                        newerEntry.entry(),
                        newerEntry.operation(),
                        List.copyOf(mergedAckIds),
                        compacted || newerEntry.compacted(),
                        newerEntry.sourceStreamKey()
                );
            }
            return new PendingEntry(
                    entry(),
                    operation(),
                    List.copyOf(mergedAckIds),
                    compacted || newerEntry.compacted(),
                    sourceStreamKey()
            );
        }
    }

    private record CompactionTokenGroup(
            StreamEntry entry,
            String namespace,
            String id,
            List<StreamEntryID> ackIds,
            long streamVersion
    ) {
        private static CompactionTokenGroup initial(StreamEntry entry, String namespace, String id) {
            return new CompactionTokenGroup(
                    entry,
                    namespace,
                    id,
                    List.of(entry.getID()),
                    parseStreamVersion(entry)
            );
        }

        private CompactionTokenGroup merge(StreamEntry newerEntry) {
            ArrayList<StreamEntryID> mergedAckIds = new ArrayList<>(ackIds.size() + 1);
            mergedAckIds.addAll(ackIds);
            mergedAckIds.add(newerEntry.getID());
            long newerVersion = parseStreamVersion(newerEntry);
            if (newerVersion >= streamVersion) {
                return new CompactionTokenGroup(
                        newerEntry,
                        namespace,
                        id,
                        List.copyOf(mergedAckIds),
                        newerVersion
                );
            }
            return new CompactionTokenGroup(
                    entry,
                    namespace,
                    id,
                    List.copyOf(mergedAckIds),
                    streamVersion
            );
        }

        private static long parseStreamVersion(StreamEntry entry) {
            String rawVersion = entry.getFields().get("version");
            if (rawVersion == null || rawVersion.isBlank()) {
                return 0L;
            }
            try {
                return Long.parseLong(rawVersion);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }

    private record FlushGroupKey(
            String tableName,
            String idColumn,
            String versionColumn,
            String operationType,
            List<String> columns
    ) {
        private static FlushGroupKey of(QueuedWriteOperation operation) {
            return new FlushGroupKey(
                    operation.tableName(),
                    operation.idColumn(),
                    operation.versionColumn(),
                    operation.type().name(),
                    List.copyOf(operation.columns().keySet())
            );
        }

        private static FlushGroupKey singleGroup() {
            return new FlushGroupKey("__single__", "__single__", "__single__", "__single__", List.of("__single__"));
        }
    }

    private record EntityKey(String namespace, String id) {
        private static EntityKey of(String namespace, String id) {
            return new EntityKey(namespace, id);
        }

        private static EntityKey of(QueuedWriteOperation operation) {
            return new EntityKey(operation.redisNamespace(), operation.id());
        }
    }
}

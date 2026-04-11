package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.ProjectionRefreshConfig;
import com.reactor.cachedb.core.config.QueryIndexConfig;
import com.reactor.cachedb.core.config.RelationConfig;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.queue.ProjectionRefreshFailureEntry;
import com.reactor.cachedb.core.queue.ProjectionRefreshReplayResult;
import com.reactor.cachedb.core.queue.ProjectionRefreshSnapshot;
import com.reactor.cachedb.core.queue.WorkerErrorCapture;
import com.reactor.cachedb.core.queue.WorkerErrorDetails;
import com.reactor.cachedb.core.projection.EntityProjectionBinding;
import com.reactor.cachedb.core.projection.ProjectionEntityCodec;
import com.reactor.cachedb.core.projection.ProjectionEntityMetadata;
import com.reactor.cachedb.core.query.QueryEvaluator;
import com.reactor.cachedb.core.registry.EntityBinding;
import com.reactor.cachedb.core.registry.EntityRegistry;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class RedisProjectionRefreshWorker implements AutoCloseable {

    private final JedisPooled jedis;
    private final ProjectionRefreshConfig config;
    private final RedisProjectionRefreshQueue queue;
    private final RedisKeyStrategy keyStrategy;
    private final EntityRegistry entityRegistry;
    private final QueryIndexConfig queryIndexConfig;
    private final RelationConfig relationConfig;
    private final QueryEvaluator queryEvaluator;
    private final RedisProducerGuard producerGuard;
    private final ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, ProjectionHandler<?, ?>> handlers = new ConcurrentHashMap<>();
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong retriedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong replayedCount = new AtomicLong();
    private final AtomicLong claimedCount = new AtomicLong();
    private final AtomicLong lastProcessedAtEpochMillis = new AtomicLong();
    private final AtomicLong lastErrorAtEpochMillis = new AtomicLong();
    private final AtomicReference<String> lastErrorType = new AtomicReference<>("");
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>("");
    private final AtomicReference<String> lastErrorRootType = new AtomicReference<>("");
    private final AtomicReference<String> lastErrorRootMessage = new AtomicReference<>("");
    private final AtomicReference<String> lastErrorOrigin = new AtomicReference<>("");
    private final AtomicReference<String> lastPoisonEntryId = new AtomicReference<>("");

    public RedisProjectionRefreshWorker(
            JedisPooled jedis,
            ProjectionRefreshConfig config,
            RedisProjectionRefreshQueue queue,
            RedisKeyStrategy keyStrategy,
            EntityRegistry entityRegistry,
            QueryIndexConfig queryIndexConfig,
            RelationConfig relationConfig,
            QueryEvaluator queryEvaluator,
            RedisProducerGuard producerGuard
    ) {
        this.jedis = jedis;
        this.config = config;
        this.queue = queue;
        this.keyStrategy = keyStrategy;
        this.entityRegistry = entityRegistry;
        this.queryIndexConfig = queryIndexConfig;
        this.relationConfig = relationConfig;
        this.queryEvaluator = queryEvaluator;
        this.producerGuard = producerGuard;
        this.executorService = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, config.consumerNamePrefix() + "-0");
            thread.setDaemon(config.daemonThreads());
            return thread;
        });
    }

    public void start() {
        if (!config.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        ensureConsumerGroup();
        executorService.submit(this::runLoop);
    }

    public ProjectionRefreshSnapshot snapshot() {
        long streamLength = streamLength(config.streamKey());
        long deadLetterStreamLength = streamLength(config.deadLetterStreamKey());
        long pendingCount = pendingCount();
        return new ProjectionRefreshSnapshot(
                streamLength,
                deadLetterStreamLength,
                processedCount.get(),
                retriedCount.get(),
                failedCount.get(),
                replayedCount.get(),
                claimedCount.get(),
                pendingCount,
                lagEstimateMillis(streamLength, deadLetterStreamLength, pendingCount),
                streamLength > 0L || deadLetterStreamLength > 0L || pendingCount > 0L,
                lastProcessedAtEpochMillis.get(),
                lastErrorAtEpochMillis.get(),
                lastErrorType.get(),
                lastErrorMessage.get(),
                lastErrorRootType.get(),
                lastErrorRootMessage.get(),
                lastErrorOrigin.get(),
                lastPoisonEntryId.get()
        );
    }

    public List<ProjectionRefreshFailureEntry> failures(int limit) {
        if (!config.deadLetterEnabled()) {
            return List.of();
        }
        int safeLimit = Math.max(1, limit);
        return jedis.xrevrange(config.deadLetterStreamKey(), "+", "-", safeLimit).stream()
                .map(this::toFailureEntry)
                .toList();
    }

    public ProjectionRefreshReplayResult replayFailure(String entryId) {
        if (!config.deadLetterEnabled()) {
            return new ProjectionRefreshReplayResult(defaultString(entryId), false, "", "Projection refresh dead-letter queue is disabled.");
        }
        if (queue == null) {
            return new ProjectionRefreshReplayResult(defaultString(entryId), false, "", "Projection refresh queue is not available.");
        }
        String trimmedEntryId = defaultString(entryId).trim();
        if (trimmedEntryId.isEmpty()) {
            return new ProjectionRefreshReplayResult("", false, "", "Projection refresh replay requires a dead-letter entry id.");
        }
        List<StreamEntry> entries = jedis.xrange(config.deadLetterStreamKey(), trimmedEntryId, trimmedEntryId, 1);
        if (entries == null || entries.isEmpty()) {
            return new ProjectionRefreshReplayResult(trimmedEntryId, false, "", "Projection refresh dead-letter entry was not found.");
        }
        StreamEntry entry = entries.get(0);
        String operation = entry.getFields().get("operation");
        String entityName = entry.getFields().get("entity");
        String projectionName = entry.getFields().get("projection");
        String rawId = entry.getFields().get("id");
        if (operation == null || entityName == null || projectionName == null || rawId == null) {
            return new ProjectionRefreshReplayResult(trimmedEntryId, false, "", "Projection refresh dead-letter entry is missing required fields.");
        }
        String originalEntryId = defaultString(entry.getFields().get("originalEntryId"));
        String enqueuedEntryId = queue.enqueueReplay(
                operation,
                entityName,
                projectionName,
                rawId,
                originalEntryId.isBlank() ? trimmedEntryId : originalEntryId,
                trimmedEntryId
        );
        jedis.xdel(config.deadLetterStreamKey(), entry.getID());
        replayedCount.incrementAndGet();
        return new ProjectionRefreshReplayResult(trimmedEntryId, true, enqueuedEntryId, "Projection refresh entry was re-enqueued.");
    }

    private void ensureConsumerGroup() {
        if (!config.autoCreateConsumerGroup()) {
            return;
        }
        try {
            jedis.xgroupCreate(config.streamKey(), config.consumerGroup(), StreamEntryID.LAST_ENTRY, true);
        } catch (JedisDataException exception) {
            if (!exception.getMessage().contains("BUSYGROUP")) {
                throw exception;
            }
        }
    }

    private void runLoop() {
        String consumerName = config.consumerNamePrefix() + "-0";
        XReadGroupParams params = new XReadGroupParams()
                .count(config.batchSize())
                .block((int) config.blockTimeoutMillis());
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                List<Entry<String, List<StreamEntry>>> responses = jedis.xreadGroup(
                        config.consumerGroup(),
                        consumerName,
                        params,
                        Map.of(config.streamKey(), StreamEntryID.UNRECEIVED_ENTRY)
                );
                if (!hasEntries(responses) && config.recoverPendingEntries()) {
                    responses = claimAbandonedEntries(consumerName);
                }
                if (!hasEntries(responses)) {
                    sleepQuietly(config.idleSleepMillis());
                    continue;
                }
                for (Entry<String, List<StreamEntry>> response : responses) {
                    processEntries(response.getValue());
                }
            } catch (RuntimeException exception) {
                sleepQuietly(config.idleSleepMillis());
            }
        }
    }

    private List<Entry<String, List<StreamEntry>>> claimAbandonedEntries(String consumerName) {
        try {
            var response = jedis.xautoclaim(
                    config.streamKey(),
                    config.consumerGroup(),
                    consumerName,
                    config.claimIdleMillis(),
                    StreamEntryID.MINIMUM_ID,
                    XAutoClaimParams.xAutoClaimParams().count(config.claimBatchSize())
            );
            List<StreamEntry> entries = response.getValue();
            if (entries == null || entries.isEmpty()) {
                return List.of();
            }
            claimedCount.addAndGet(entries.size());
            return List.of(Map.entry(config.streamKey(), entries));
        } catch (JedisDataException exception) {
            return List.of();
        }
    }

    private void processEntries(List<StreamEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        ArrayList<StreamEntryID> ackIds = new ArrayList<>(entries.size());
        for (StreamEntry entry : entries) {
            if (processEntry(entry)) {
                ackIds.add(entry.getID());
            }
        }
        if (!ackIds.isEmpty()) {
            jedis.xack(config.streamKey(), config.consumerGroup(), ackIds.toArray(StreamEntryID[]::new));
        }
    }

    private boolean processEntry(StreamEntry entry) {
        try {
            String entityName = entry.getFields().get("entity");
            String projectionName = entry.getFields().get("projection");
            String rawId = entry.getFields().get("id");
            String operation = entry.getFields().get("operation");
            if (entityName == null || projectionName == null || rawId == null || operation == null) {
                return handleProcessingFailure(entry, new IllegalStateException("Projection refresh entry is missing required fields."));
            }
            ProjectionHandler<?, ?> rawHandler = handler(entityName, projectionName);
            if (rawHandler == null) {
                return handleProcessingFailure(entry, new IllegalStateException("Projection refresh handler not found for " + entityName + "#" + projectionName));
            }
            return processWithHandler(entry, rawHandler, rawId, operation);
        } catch (RuntimeException exception) {
            return handleProcessingFailure(entry, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private <T, ID> boolean processWithHandler(StreamEntry entry, ProjectionHandler<?, ?> rawHandler, String rawId, String operation) {
        ProjectionHandler<T, ID> handler = (ProjectionHandler<T, ID>) rawHandler;
        if ("DELETE".equalsIgnoreCase(operation)) {
            handler.runtime().delete(handler.parseId(rawId));
            markProcessed();
            return true;
        }
        List<String> values = jedis.mget(
                keyStrategy.entityKey(handler.baseMetadata().redisNamespace(), rawId),
                keyStrategy.tombstoneKey(handler.baseMetadata().redisNamespace(), rawId)
        );
        if (values.get(1) != null || values.get(0) == null) {
            handler.runtime().delete(handler.parseId(rawId));
            markProcessed();
            return true;
        }
        T entity = handler.codec().fromRedisValue(values.get(0));
        Object projection = handler.binding().projection().projector().apply(entity);
        if (projection == null) {
            handler.runtime().delete(handler.parseId(rawId));
            markProcessed();
            return true;
        }
        upsertProjection(handler, projection);
        markProcessed();
        return true;
    }

    @SuppressWarnings("unchecked")
    private <ID> void upsertProjection(ProjectionHandler<?, ID> handler, Object projection) {
        ((RedisProjectionRuntime<Object, ID>) handler.runtime()).upsert(projection);
    }

    private ProjectionHandler<?, ?> handler(String entityName, String projectionName) {
        return handlers.computeIfAbsent(entityName + "#" + projectionName, ignored -> createHandler(entityName, projectionName));
    }

    @SuppressWarnings("unchecked")
    private <T, ID, P> ProjectionHandler<T, ID> createHandler(String entityName, String projectionName) {
        Optional<EntityBinding<?, ?>> entityBindingOptional = entityRegistry.find(entityName);
        Optional<EntityProjectionBinding<?, ?, ?>> projectionBindingOptional = entityRegistry.findProjection(entityName, projectionName);
        if (entityBindingOptional.isEmpty() || projectionBindingOptional.isEmpty()) {
            return null;
        }
        EntityBinding<T, ID> entityBinding = (EntityBinding<T, ID>) entityBindingOptional.get();
        EntityProjectionBinding<T, P, ID> projectionBinding = (EntityProjectionBinding<T, P, ID>) projectionBindingOptional.get();
        ProjectionEntityMetadata<P, ID> projectionMetadata = new ProjectionEntityMetadata<>(entityBinding.metadata(), projectionBinding.projection());
        ProjectionEntityCodec<P, ID> projectionCodec = new ProjectionEntityCodec<>(projectionBinding.projection());
        RedisQueryIndexManager<P, ID> queryIndexManager = new RedisQueryIndexManager<>(
                jedis,
                projectionMetadata,
                projectionCodec,
                keyStrategy,
                entityRegistry,
                queryIndexConfig,
                relationConfig,
                queryEvaluator,
                producerGuard
        );
        RedisProjectionRuntime<P, ID> runtime = new RedisProjectionRuntime<>(
                jedis,
                projectionMetadata,
                projectionCodec,
                keyStrategy,
                entityBinding.cachePolicy(),
                queryIndexManager,
                projectionBinding
        );
        return new ProjectionHandler<>(entityBinding.metadata(), entityBinding.codec(), projectionBinding, runtime);
    }

    private boolean handleProcessingFailure(StreamEntry entry, RuntimeException exception) {
        captureError(exception);
        int attempt = parsePositiveInt(entry.getFields().get("attempt"), 1);
        if (!config.deadLetterEnabled()) {
            return false;
        }
        if (queue == null) {
            return false;
        }
        if (attempt >= Math.max(1, config.maxAttempts())) {
            return moveToDeadLetter(entry, attempt, exception);
        }
        return requeue(entry, attempt + 1);
    }

    private boolean requeue(StreamEntry entry, int nextAttempt) {
        String operation = entry.getFields().get("operation");
        String entityName = entry.getFields().get("entity");
        String projectionName = entry.getFields().get("projection");
        String rawId = entry.getFields().get("id");
        if (operation == null || entityName == null || projectionName == null || rawId == null) {
            return false;
        }
        String originalEntryId = defaultString(entry.getFields().get("originalEntryId"));
        queue.enqueueRetry(
                operation,
                entityName,
                projectionName,
                rawId,
                nextAttempt,
                originalEntryId.isBlank() ? entry.getID().toString() : originalEntryId
        );
        retriedCount.incrementAndGet();
        return true;
    }

    private boolean moveToDeadLetter(StreamEntry entry, int attempt, RuntimeException exception) {
        WorkerErrorDetails error = WorkerErrorCapture.capture(exception);
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("operation", defaultString(entry.getFields().get("operation")));
        fields.put("entity", defaultString(entry.getFields().get("entity")));
        fields.put("projection", defaultString(entry.getFields().get("projection")));
        fields.put("id", defaultString(entry.getFields().get("id")));
        fields.put("attempt", String.valueOf(Math.max(1, attempt)));
        fields.put("failedAtEpochMillis", String.valueOf(System.currentTimeMillis()));
        fields.put("originalEntryId", defaultString(entry.getFields().get("originalEntryId")).isBlank()
                ? entry.getID().toString()
                : defaultString(entry.getFields().get("originalEntryId")));
        fields.put("errorType", defaultString(error.errorType()));
        fields.put("errorMessage", defaultString(error.errorMessage()));
        fields.put("errorRootType", defaultString(error.rootErrorType()));
        fields.put("errorRootMessage", defaultString(error.rootErrorMessage()));
        fields.put("errorOrigin", defaultString(error.origin()));
        String deadLetterEntryId = jedis.xadd(config.deadLetterStreamKey(), XAddParams.xAddParams(), fields).toString();
        if (config.deadLetterMaxLength() > 0) {
            jedis.xtrim(config.deadLetterStreamKey(), config.deadLetterMaxLength(), true);
        }
        failedCount.incrementAndGet();
        lastPoisonEntryId.set(deadLetterEntryId);
        return true;
    }

    private ProjectionRefreshFailureEntry toFailureEntry(StreamEntry entry) {
        Map<String, String> fields = entry.getFields();
        return new ProjectionRefreshFailureEntry(
                entry.getID().toString(),
                defaultString(fields.get("originalEntryId")),
                defaultString(fields.get("entity")),
                defaultString(fields.get("projection")),
                defaultString(fields.get("id")),
                defaultString(fields.get("operation")),
                parsePositiveInt(fields.get("attempt"), 1),
                parseLong(fields.get("failedAtEpochMillis"), 0L),
                defaultString(fields.get("errorType")),
                defaultString(fields.get("errorMessage")),
                defaultString(fields.get("errorRootType")),
                defaultString(fields.get("errorRootMessage")),
                defaultString(fields.get("errorOrigin"))
        );
    }

    private void markProcessed() {
        processedCount.incrementAndGet();
        lastProcessedAtEpochMillis.set(System.currentTimeMillis());
    }

    private void captureError(RuntimeException exception) {
        WorkerErrorDetails error = WorkerErrorCapture.capture(exception);
        lastErrorAtEpochMillis.set(System.currentTimeMillis());
        lastErrorType.set(defaultString(error.errorType()));
        lastErrorMessage.set(defaultString(error.errorMessage()));
        lastErrorRootType.set(defaultString(error.rootErrorType()));
        lastErrorRootMessage.set(defaultString(error.rootErrorMessage()));
        lastErrorOrigin.set(defaultString(error.origin()));
    }

    private long streamLength(String streamKey) {
        if (streamKey == null || streamKey.isBlank()) {
            return 0L;
        }
        try {
            return jedis.xlen(streamKey);
        } catch (RuntimeException exception) {
            return 0L;
        }
    }

    private long pendingCount() {
        if (!config.recoverPendingEntries()) {
            return 0L;
        }
        try {
            return jedis.xpending(config.streamKey(), config.consumerGroup()).getTotal();
        } catch (RuntimeException exception) {
            return 0L;
        }
    }

    private long lagEstimateMillis(long streamLength, long deadLetterStreamLength, long pendingCount) {
        if (streamLength <= 0L && deadLetterStreamLength <= 0L && pendingCount <= 0L) {
            return 0L;
        }
        long lastProcessedAt = lastProcessedAtEpochMillis.get();
        if (lastProcessedAt <= 0L) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - lastProcessedAt);
    }

    private int parsePositiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
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

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running.set(false);
        executorService.shutdownNow();
        try {
            executorService.awaitTermination(config.shutdownAwaitMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private record ProjectionHandler<T, ID>(
            EntityMetadata<T, ID> baseMetadata,
            EntityCodec<T> codec,
            EntityProjectionBinding<T, ?, ID> binding,
            RedisProjectionRuntime<?, ID> runtime
    ) {
        @SuppressWarnings("unchecked")
        ID parseId(String rawId) {
            Object parsed = rawId;
            if (rawId.matches("-?\\d+")) {
                try {
                    parsed = Long.valueOf(rawId);
                } catch (NumberFormatException ignored) {
                    parsed = rawId;
                }
            }
            return (ID) parsed;
        }
    }
}

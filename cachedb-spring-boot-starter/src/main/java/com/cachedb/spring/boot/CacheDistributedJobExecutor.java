package com.reactor.cachedb.spring.boot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Executes typed jobs through a Redis Stream consumer group. The command and
 * its arguments remain in Redis until a handler completes, so another pod can
 * claim abandoned work. The legacy Supplier API remains node-local by design.
 */
public final class CacheDistributedJobExecutor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheDistributedJobExecutor.class);
    private static final String FIELD_JOB_ID = "jobId";
    private static final String FIELD_ROUTE = "route";
    private static final String FIELD_PAYLOAD = "payloadJson";
    private static final String ENQUEUE_IF_CAPACITY_SCRIPT = """
            if redis.call('XLEN', KEYS[1]) >= tonumber(ARGV[1]) then
                return false
            end
            return redis.call(
                'XADD', KEYS[1], '*',
                'jobId', ARGV[2],
                'route', ARGV[3],
                'payloadJson', ARGV[4]
            )
            """;

    private final JedisPooled jedis;
    private final ObjectMapper objectMapper;
    private final String ownerInstanceId;
    private final String keyPrefix;
    private final String streamKey;
    private final String consumerGroup;
    private final int statusTtlSeconds;
    private final int maxResultBytes;
    private final long shutdownAwaitMillis;
    private final int queueCapacity;
    private final int defaultMaxAttempts;
    private final long claimIdleMillis;
    private final int pollBlockMillis;
    private final long retryBackoffMillis;
    private final ThreadPoolExecutor localExecutor;
    private final ExecutorService streamWorkers;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Map<String, CacheDistributedJobHandler<?>> handlers;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public CacheDistributedJobExecutor(
            JedisPooled jedis,
            ObjectMapper objectMapper,
            String ownerInstanceId,
            CacheDbSpringProperties.JobExecutorProperties properties
    ) {
        this(jedis, objectMapper, ownerInstanceId, properties, List.of());
    }

    public CacheDistributedJobExecutor(
            JedisPooled jedis,
            ObjectMapper objectMapper,
            String ownerInstanceId,
            CacheDbSpringProperties.JobExecutorProperties properties,
            Collection<? extends CacheDistributedJobHandler<?>> handlers
    ) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.ownerInstanceId = requireText(ownerInstanceId, "ownerInstanceId");
        Objects.requireNonNull(properties, "properties");
        this.keyPrefix = normalizePrefix(properties.getKeyPrefix());
        this.streamKey = keyPrefix + ":stream";
        this.consumerGroup = requireText(properties.getConsumerGroup(), "consumerGroup");
        this.statusTtlSeconds = Math.max(60, properties.getStatusTtlSeconds());
        this.maxResultBytes = Math.max(1_024, properties.getMaxResultBytes());
        this.shutdownAwaitMillis = Math.max(0L, properties.getShutdownAwaitMillis());
        this.queueCapacity = Math.max(1, properties.getQueueCapacity());
        this.defaultMaxAttempts = Math.max(1, properties.getMaxAttempts());
        this.claimIdleMillis = Math.max(1_000L, properties.getClaimIdleMillis());
        this.pollBlockMillis = Math.max(100, properties.getPollBlockMillis());
        this.retryBackoffMillis = Math.max(0L, properties.getRetryBackoffMillis());
        int workers = Math.max(1, properties.getWorkerThreads());
        this.localExecutor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> daemonThread(runnable, "cachedb-local-job-" + ownerInstanceId),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.handlers = handlerMap(handlers);
        this.heartbeatExecutor = Executors.newScheduledThreadPool(
                Math.min(workers, 2),
                runnable -> daemonThread(runnable, "cachedb-job-heartbeat-" + ownerInstanceId)
        );
        if (this.handlers.isEmpty()) {
            this.streamWorkers = null;
        } else {
            ensureConsumerGroup();
            this.streamWorkers = Executors.newFixedThreadPool(
                    workers,
                    runnable -> daemonThread(runnable, "cachedb-job-worker-" + ownerInstanceId)
            );
            for (int index = 0; index < workers; index++) {
                this.streamWorkers.execute(this::workerLoop);
            }
        }
    }

    /**
     * Submits a durable typed command. A handler with the same route must be
     * registered on every pod that participates in the consumer group.
     */
    public CacheDistributedJobSnapshot submit(String route, Object arguments) {
        String normalizedRoute = requireText(route, "route");
        CacheDistributedJobHandler<?> handler = handlers.get(normalizedRoute);
        if (handler == null) {
            throw new IllegalArgumentException("No distributed job handler is registered for route=" + normalizedRoute);
        }
        handler.definition().requireArguments(arguments);
        String payloadJson = writeJson(arguments);
        String jobId = UUID.randomUUID().toString();
        long submittedAt = Instant.now().toEpochMilli();
        CacheDistributedJobSnapshot queued = queued(jobId, normalizedRoute, submittedAt);
        store(queued, null, payloadJson, 0, null);
        try {
            Object streamEntryId = jedis.eval(
                    ENQUEUE_IF_CAPACITY_SCRIPT,
                    List.of(streamKey),
                    List.of(Integer.toString(queueCapacity), jobId, normalizedRoute, payloadJson)
            );
            if (streamEntryId == null) {
                jedis.del(key(jobId));
                throw queueFull();
            }
            return queued;
        } catch (RuntimeException exception) {
            jedis.del(key(jobId));
            throw exception;
        }
    }

    public <A> CacheDistributedJobSnapshot submit(
            CacheDistributedJobDefinition<A> definition,
            A arguments
    ) {
        CacheDistributedJobDefinition<A> resolved = Objects.requireNonNull(definition, "definition");
        A typedArguments = resolved.requireArguments(arguments);
        CacheDistributedJobHandler<?> handler = handlers.get(resolved.route());
        if (handler == null) {
            throw new IllegalArgumentException("No distributed job handler is registered for route=" + resolved.route());
        }
        if (!handler.definition().argumentType().equals(resolved.argumentType())) {
            throw new IllegalArgumentException("Distributed job definition for route=" + resolved.route()
                    + " does not match the registered handler argument type");
        }
        return submit(resolved.route(), typedArguments);
    }

    /**
     * Compatibility surface for in-process work. This overload cannot fail
     * over to another pod because a Java closure cannot be serialized safely.
     */
    @Deprecated(since = "0.6.0", forRemoval = false)
    public CacheDistributedJobSnapshot submit(String route, Supplier<?> operation) {
        Objects.requireNonNull(operation, "operation");
        String normalizedRoute = requireText(route, "route");
        String jobId = UUID.randomUUID().toString();
        long submittedAt = Instant.now().toEpochMilli();
        CacheDistributedJobSnapshot queued = queued(jobId, normalizedRoute, submittedAt);
        store(queued, null, null, 0, null);
        try {
            localExecutor.execute(() -> runLocal(queued, operation));
        } catch (RejectedExecutionException exception) {
            jedis.del(key(jobId));
            throw queueFull();
        }
        return queued;
    }

    public Optional<CacheDistributedJobSnapshot> find(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> fields = jedis.hgetAll(key(jobId.trim()));
        return fields == null || fields.isEmpty() ? Optional.empty() : Optional.of(fromFields(fields));
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        shutdown(streamWorkers, true);
        shutdown(heartbeatExecutor, true);
        shutdown(localExecutor, true);
    }

    private void workerLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                List<StreamEntry> claimed = claimAbandoned();
                if (!claimed.isEmpty()) {
                    claimed.forEach(this::processEntry);
                    continue;
                }
                var streams = jedis.xreadGroup(
                        consumerGroup,
                        ownerInstanceId,
                        XReadGroupParams.xReadGroupParams().count(1).block(pollBlockMillis),
                        Map.of(streamKey, StreamEntryID.UNRECEIVED_ENTRY)
                );
                if (streams == null) {
                    continue;
                }
                streams.forEach(stream -> stream.getValue().forEach(this::processEntry));
            } catch (RuntimeException exception) {
                if (running.get()) {
                    LOGGER.warn("CacheDB distributed job worker failed; polling will retry", exception);
                    sleep(Math.max(100L, retryBackoffMillis));
                }
            }
        }
    }

    private List<StreamEntry> claimAbandoned() {
        var claimed = jedis.xautoclaim(
                streamKey,
                consumerGroup,
                ownerInstanceId,
                claimIdleMillis,
                StreamEntryID.MINIMUM_ID,
                XAutoClaimParams.xAutoClaimParams().count(8)
        );
        return claimed == null || claimed.getValue() == null ? List.of() : claimed.getValue();
    }

    private void processEntry(StreamEntry entry) {
        Map<String, String> fields = entry.getFields();
        String jobId = fields.get(FIELD_JOB_ID);
        String route = fields.get(FIELD_ROUTE);
        String payloadJson = fields.get(FIELD_PAYLOAD);
        if (jobId == null || route == null || payloadJson == null) {
            terminalFailure(entry, jobId, route, "InvalidJobCommand", "Stored job command is incomplete", 1);
            return;
        }
        Optional<CacheDistributedJobSnapshot> existing = find(jobId);
        if (existing.isPresent() && isTerminal(existing.get().status())) {
            acknowledge(entry);
            return;
        }
        CacheDistributedJobHandler<?> handler = handlers.get(route);
        if (handler == null) {
            terminalFailure(entry, jobId, route, "MissingHandler", "No handler is registered for route=" + route, 1);
            return;
        }
        Map<String, String> storedFields = jedis.hgetAll(key(jobId));
        int attempt = parseInt(storedFields.get("attempt"), 0) + 1;
        long submittedAt = parseLong(storedFields.get("submittedAtEpochMillis"), Instant.now().toEpochMilli());
        long startedAt = Instant.now().toEpochMilli();
        CacheDistributedJobSnapshot runningSnapshot = new CacheDistributedJobSnapshot(
                jobId, route, CacheDistributedJobState.RUNNING, ownerInstanceId,
                submittedAt, startedAt, null, null, null
        );
        store(runningSnapshot, null, payloadJson, attempt, storedFields.get("checkpointJson"));
        ScheduledFuture<?> heartbeat = heartbeat(entry, jobId);
        try {
            Object result = execute(handler, payloadJson, new RedisJobContext(jobId, route, attempt));
            String resultJson = writeJson(result);
            requireResultSize(resultJson);
            store(new CacheDistributedJobSnapshot(
                    jobId, route, CacheDistributedJobState.COMPLETED, ownerInstanceId,
                    submittedAt, startedAt, Instant.now().toEpochMilli(), objectMapper.readTree(resultJson), null
            ), resultJson, payloadJson, attempt, currentCheckpoint(jobId));
            acknowledge(entry);
        } catch (RuntimeException | JsonProcessingException exception) {
            if (attempt < defaultMaxAttempts && running.get()) {
                LOGGER.warn("CacheDB job {} failed on attempt {}; it remains pending for another claim", jobId, attempt, exception);
                store(new CacheDistributedJobSnapshot(
                        jobId, route, CacheDistributedJobState.QUEUED, ownerInstanceId,
                        submittedAt, startedAt, null, null,
                        new CacheDistributedJobSnapshot.JobError(
                                exception.getClass().getSimpleName(),
                                "Attempt " + attempt + " failed; the command will be reclaimed"
                        )
                ), null, payloadJson, attempt, currentCheckpoint(jobId));
                sleep(retryBackoffMillis);
            } else {
                terminalFailure(
                        entry, jobId, route, exception.getClass().getSimpleName(),
                        "Job failed after " + attempt + " attempt(s); inspect logs using jobId=" + jobId,
                        attempt
                );
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    private ScheduledFuture<?> heartbeat(StreamEntry entry, String jobId) {
        long interval = Math.max(500L, claimIdleMillis / 3L);
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                jedis.xclaimJustId(
                        streamKey,
                        consumerGroup,
                        ownerInstanceId,
                        0L,
                        redis.clients.jedis.params.XClaimParams.xClaimParams(),
                        entry.getID()
                );
                jedis.hset(key(jobId), Map.of("heartbeatAtEpochMillis", Long.toString(Instant.now().toEpochMilli())));
                jedis.expire(key(jobId), statusTtlSeconds);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not renew distributed job heartbeat for jobId={}", jobId, exception);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void terminalFailure(
            StreamEntry entry,
            String jobId,
            String route,
            String errorType,
            String message,
            int attempt
    ) {
        String safeJobId = jobId == null ? "unknown-" + entry.getID() : jobId;
        String safeRoute = route == null ? "unknown" : route;
        Map<String, String> stored = jedis.hgetAll(key(safeJobId));
        long submittedAt = parseLong(stored.get("submittedAtEpochMillis"), Instant.now().toEpochMilli());
        store(new CacheDistributedJobSnapshot(
                safeJobId, safeRoute, CacheDistributedJobState.FAILED, ownerInstanceId,
                submittedAt, parseNullableLong(stored.get("startedAtEpochMillis")),
                Instant.now().toEpochMilli(), null,
                new CacheDistributedJobSnapshot.JobError(errorType, message)
        ), null, stored.get(FIELD_PAYLOAD), attempt, stored.get("checkpointJson"));
        acknowledge(entry);
    }

    private void acknowledge(StreamEntry entry) {
        jedis.xack(streamKey, consumerGroup, entry.getID());
        jedis.xdel(streamKey, entry.getID());
    }

    private void runLocal(CacheDistributedJobSnapshot queued, Supplier<?> operation) {
        long startedAt = Instant.now().toEpochMilli();
        store(new CacheDistributedJobSnapshot(
                queued.jobId(), queued.route(), CacheDistributedJobState.RUNNING, ownerInstanceId,
                queued.submittedAtEpochMillis(), startedAt, null, null, null
        ), null, null, 1, null);
        try {
            String resultJson = writeJson(operation.get());
            requireResultSize(resultJson);
            store(new CacheDistributedJobSnapshot(
                    queued.jobId(), queued.route(), CacheDistributedJobState.COMPLETED, ownerInstanceId,
                    queued.submittedAtEpochMillis(), startedAt, Instant.now().toEpochMilli(),
                    objectMapper.readTree(resultJson), null
            ), resultJson, null, 1, null);
        } catch (RuntimeException | JsonProcessingException exception) {
            LOGGER.error("Node-local job {} failed for route {}", queued.jobId(), queued.route(), exception);
            store(new CacheDistributedJobSnapshot(
                    queued.jobId(), queued.route(), CacheDistributedJobState.FAILED, ownerInstanceId,
                    queued.submittedAtEpochMillis(), startedAt, Instant.now().toEpochMilli(), null,
                    new CacheDistributedJobSnapshot.JobError(
                            exception.getClass().getSimpleName(),
                            "Job failed; inspect server logs using jobId=" + queued.jobId()
                    )
            ), null, null, 1, null);
        }
    }

    private void store(
            CacheDistributedJobSnapshot snapshot,
            String resultJson,
            String payloadJson,
            int attempt,
            String checkpointJson
    ) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_JOB_ID, snapshot.jobId());
        fields.put(FIELD_ROUTE, snapshot.route());
        fields.put("status", snapshot.status().name());
        fields.put("ownerInstanceId", snapshot.ownerInstanceId());
        fields.put("submittedAtEpochMillis", Long.toString(snapshot.submittedAtEpochMillis()));
        fields.put("attempt", Integer.toString(Math.max(0, attempt)));
        putLong(fields, "startedAtEpochMillis", snapshot.startedAtEpochMillis());
        putLong(fields, "finishedAtEpochMillis", snapshot.finishedAtEpochMillis());
        putText(fields, "resultJson", resultJson);
        putText(fields, FIELD_PAYLOAD, payloadJson);
        putText(fields, "checkpointJson", checkpointJson);
        if (snapshot.error() != null) {
            fields.put("errorType", snapshot.error().type());
            fields.put("errorMessage", snapshot.error().message());
        }
        String redisKey = key(snapshot.jobId());
        jedis.hdel(redisKey, "resultJson", "errorType", "errorMessage");
        jedis.hset(redisKey, fields);
        jedis.expire(redisKey, statusTtlSeconds);
    }

    private CacheDistributedJobSnapshot fromFields(Map<String, String> fields) {
        JsonNode result = readTree(fields.get("resultJson"));
        CacheDistributedJobSnapshot.JobError error = fields.containsKey("errorType")
                ? new CacheDistributedJobSnapshot.JobError(fields.get("errorType"), fields.get("errorMessage"))
                : null;
        return new CacheDistributedJobSnapshot(
                fields.get(FIELD_JOB_ID),
                fields.get(FIELD_ROUTE),
                CacheDistributedJobState.valueOf(fields.get("status")),
                fields.getOrDefault("ownerInstanceId", "unknown"),
                parseLong(fields.get("submittedAtEpochMillis"), 0L),
                parseNullableLong(fields.get("startedAtEpochMillis")),
                parseNullableLong(fields.get("finishedAtEpochMillis")),
                result,
                error
        );
    }

    private void ensureConsumerGroup() {
        try {
            jedis.xgroupCreate(streamKey, consumerGroup, new StreamEntryID(0L, 0L), true);
        } catch (JedisDataException exception) {
            if (exception.getMessage() == null || !exception.getMessage().contains("BUSYGROUP")) {
                throw exception;
            }
        }
    }

    private Map<String, CacheDistributedJobHandler<?>> handlerMap(
            Collection<? extends CacheDistributedJobHandler<?>> candidates
    ) {
        Map<String, CacheDistributedJobHandler<?>> result = new ConcurrentHashMap<>();
        for (CacheDistributedJobHandler<?> handler : candidates) {
            CacheDistributedJobDefinition<?> definition = Objects.requireNonNull(
                    handler.definition(), "handler.definition"
            );
            String route = definition.route();
            CacheDistributedJobHandler<?> previous = result.putIfAbsent(route, handler);
            if (previous != null) {
                throw new IllegalStateException("Multiple distributed job handlers use route=" + route);
            }
        }
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private <A> Object execute(
            CacheDistributedJobHandler<?> rawHandler,
            String payloadJson,
            CacheDistributedJobContext context
    ) throws JsonProcessingException {
        CacheDistributedJobHandler<A> handler = (CacheDistributedJobHandler<A>) rawHandler;
        A arguments = objectMapper.readValue(payloadJson, handler.definition().argumentType());
        return handler.execute(arguments, context);
    }

    private String currentCheckpoint(String jobId) {
        return jedis.hget(key(jobId), "checkpointJson");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Distributed job arguments must be JSON serializable", exception);
        }
    }

    private JsonNode readTree(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored distributed job JSON is invalid", exception);
        }
    }

    private void requireResultSize(String resultJson) {
        if (resultJson.getBytes(StandardCharsets.UTF_8).length > maxResultBytes) {
            throw new IllegalStateException("Job result exceeds configured maxResultBytes=" + maxResultBytes);
        }
    }

    private CacheDistributedJobQueueFullException queueFull() {
        return new CacheDistributedJobQueueFullException(
                "Distributed job queue is full. Retry after an existing job completes; capacity=" + queueCapacity
        );
    }

    private CacheDistributedJobSnapshot queued(String jobId, String route, long submittedAt) {
        return new CacheDistributedJobSnapshot(
                jobId, route, CacheDistributedJobState.QUEUED, ownerInstanceId,
                submittedAt, null, null, null, null
        );
    }

    private void shutdown(ExecutorService executor, boolean interrupt) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        if (!awaitTermination(executor, shutdownAwaitMillis) && interrupt) {
            executor.shutdownNow();
            awaitTermination(executor, Math.min(1_000L, shutdownAwaitMillis));
        }
    }

    private boolean awaitTermination(ExecutorService executor, long timeoutMillis) {
        try {
            return executor.awaitTermination(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private String key(String jobId) {
        return keyPrefix + ":job:" + jobId;
    }

    private static boolean isTerminal(CacheDistributedJobState state) {
        return state == CacheDistributedJobState.COMPLETED
                || state == CacheDistributedJobState.FAILED
                || state == CacheDistributedJobState.CANCELLED;
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void putLong(Map<String, String> fields, String name, Long value) {
        if (value != null) {
            fields.put(name, Long.toString(value));
        }
    }

    private static void putText(Map<String, String> fields, String name, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(name, value);
        }
    }

    private static Long parseNullableLong(String value) {
        return value == null || value.isBlank() ? null : parseLong(value, 0L);
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String normalizePrefix(String value) {
        String prefix = requireText(value, "keyPrefix");
        while (prefix.endsWith(":")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private final class RedisJobContext implements CacheDistributedJobContext {
        private final String jobId;
        private final String route;
        private final int attempt;

        private RedisJobContext(String jobId, String route, int attempt) {
            this.jobId = jobId;
            this.route = route;
            this.attempt = attempt;
        }

        @Override
        public String jobId() {
            return jobId;
        }

        @Override
        public String route() {
            return route;
        }

        @Override
        public int attempt() {
            return attempt;
        }

        @Override
        public Optional<JsonNode> checkpoint() {
            return Optional.ofNullable(readTree(currentCheckpoint(jobId)));
        }

        @Override
        public void checkpoint(Object value) {
            String json = writeJson(value);
            jedis.hset(key(jobId), Map.of("checkpointJson", json));
            jedis.expire(key(jobId), statusTtlSeconds);
        }
    }
}

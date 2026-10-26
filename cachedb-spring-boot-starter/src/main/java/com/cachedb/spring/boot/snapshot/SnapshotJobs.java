package com.reactor.cachedb.spring.boot.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.core.codec.JsonArrayCodec;
import com.reactor.cachedb.redis.JedisPooledOperations;
import com.reactor.cachedb.redis.RedisClientOperations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.SetParams;

/** Framework-owned bounded snapshot preparation, coordination and generation publication. */
public final class SnapshotJobs implements SnapshotOperations, SmartLifecycle, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotJobs.class);
    private static final String RENEW =
            "if redis.call('GET',KEYS[1]) == ARGV[1] then return"
                    + " redis.call('PEXPIRE',KEYS[1],ARGV[2]) end return 0";
    private static final String RELEASE =
            "if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) end"
                    + " return 0";
    private static final String COMMIT =
            """
if redis.call('GET',KEYS[1]) ~= ARGV[1] then return {0,''} end
if redis.call('HLEN',KEYS[2]) ~= tonumber(ARGV[5]) then return {0,''} end
if ARGV[6] == '1' and redis.call('HLEN',KEYS[5]) ~= tonumber(ARGV[5]) then return {0,''} end
local previous=redis.call('GET',KEYS[3]) or ''
redis.call('SET',KEYS[3],ARGV[2],'PX',ARGV[3])
redis.call('PEXPIRE',KEYS[2],ARGV[3])
redis.call('SET',KEYS[4],ARGV[4])
return {1,previous}
""";
    private static final String CLEAN =
            """
            local active=redis.call('GET',KEYS[1]) or ''
            if string.sub(active,1,string.len(ARGV[1])+1) ~= ARGV[1]..':' then
              for i=3,#KEYS do redis.call('UNLINK',KEYS[i]) end
              return redis.call('UNLINK',KEYS[2])
            end
            return 0
            """;
    private static final String CLEAN_CONTENT =
            """
local active=redis.call('GET',KEYS[1]) or ''
local gen=string.match(active,'^([^:]+):') or ''
if gen == ARGV[2] then return 0 end
local staging=redis.call('GET',KEYS[2]) or ''
for i=3,#ARGV do
  if redis.call('SISMEMBER',ARGV[1]..':digests:'..gen,ARGV[i]) == 0
     and (staging == ARGV[2] or redis.call('SISMEMBER',ARGV[1]..':digests:'..staging,ARGV[i]) == 0) then
    redis.call('UNLINK',ARGV[1]..':content:'..ARGV[i])
  end
end
return 1
""";
    private final DataSource dataSource;
    private final RedisClientOperations redis;
    private final RedisClientOperations readRedis;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledExecutorService heartbeat;
    private final List<ScheduledFuture<?>> schedules = new ArrayList<>();
    private volatile boolean running;
    private volatile boolean closed;
    private boolean schedulingEnabled = true;
    private AutoCloseable changeRegistration;
    private com.reactor.cachedb.starter.CacheDatabase changeDatabase;

    /** SQL-backed snapshots observe committed changes, never a producer's not-yet-durable receipt. */
    public synchronized SnapshotJobs withDatabase(com.reactor.cachedb.starter.CacheDatabase database) {
        if (running || changeRegistration != null) throw new IllegalStateException("Configure the dirty bridge before start");
        changeDatabase = Objects.requireNonNull(database);
        Map<String, List<DirtyTarget>> targets = new LinkedHashMap<>();
        for (Job job : jobs.values()) for (SnapshotDirtyBinding binding : job.plan.autoDirtyBindings()) {
            if (job.plan.rootScope() == null)
                throw new IllegalArgumentException("Auto-dirty requires an explicit scopedBy/rootScope for " + job.plan.name());
            if (database.entityRegistry().find(binding.entityName()).isEmpty())
                throw new IllegalArgumentException("Unknown auto-dirty entity: " + binding.entityName());
            targets.computeIfAbsent(binding.entityName(), ignored -> new ArrayList<>()).add(new DirtyTarget(job, binding));
        }
        if (!targets.isEmpty()) changeRegistration = database.onDurableEntityChange(operation -> {
            if (closed) return;
            for (DirtyTarget target : targets.getOrDefault(operation.entityName(), List.of())) {
                Job job = target.job();
                if (!job.settings.enabled()) continue;
                try {
                    String id;
                    if (target.binding().rootEntity()) id = operation.id();
                    else {
                        // A partial/delete event cannot reconstruct a complete child or its previous parent.
                        // Keep reconciliation authoritative rather than guessing a root ID or issuing N point loads.
                        job.forceFull.set(true);
                        var entity = database.entityRegistry().find(operation.entityName()).orElseThrow();
                        if (operation.type() != com.reactor.cachedb.core.model.OperationType.UPSERT
                                || !operation.columns().keySet().containsAll(entity.metadata().columns())) continue;
                        Object changed = entity.codec().fromColumns(new LinkedHashMap<>(operation.columns()));
                        id = target.binding().rootIdExtractor().apply(changed);
                    }
                    queueDirtyRoot(job, id);
                } catch (RuntimeException failure) {
                    job.forceFull.set(true);
                    LOG.warn("Auto-dirty mapping requires reconciliation: plan={}", job.plan.name());
                }
            }
        });
        return this;
    }

    private record DirtyTarget(Job job, SnapshotDirtyBinding binding) {}

    private void queueDirtyRoot(Job job, String id) {
        if (id == null || id.isBlank() || id.length() > 512 || id.indexOf('\n') >= 0) {
            job.forceFull.set(true);
            return;
        }
        if (job.changedRoots.contains(id)) return;
        int current;
        do {
            current = job.queuedRoots.get();
            if (current >= job.settings.batchRows()) { job.forceFull.set(true); return; }
        } while (!job.queuedRoots.compareAndSet(current, current + 1));
        if (!job.changedRoots.add(id)) job.queuedRoots.decrementAndGet();
    }

    private void dirtyTick(Job job) {
        if (closed || !job.dirtyBusy.compareAndSet(false, true)) return;
        try {
            var roots = new ArrayList<String>();
            for (String id : job.changedRoots) {
                if (roots.size() >= job.settings.batchRows()) break;
                if (job.changedRoots.remove(id)) { job.queuedRoots.decrementAndGet(); roots.add(id); }
            }
            if (!roots.isEmpty()) {
                try { markDirty(job.plan.name(), roots); }
                catch (RuntimeException capacityOrRedisFailure) {
                    job.forceFull.set(true);
                    LOG.warn("Auto-dirty admission failed; full reconciliation is due: plan={}", job.plan.name());
                }
            }
            boolean automatic = !job.settings.dirtyFlushInterval().isZero() || job.settings.dirtyFlushThreshold() > 0;
            if (!automatic || job.busy.get()) return;
            if (job.forceFull.getAndSet(false)) {
                var result = refresh(job.plan.name(), true);
                if (!"COMPLETED".equals(result.status())) job.forceFull.set(true);
                return;
            }
            long now = clock.millis();
            boolean interval = !job.settings.dirtyFlushInterval().isZero()
                    && now - job.lastDirtyFlushMillis >= job.settings.dirtyFlushInterval().toMillis();
            boolean threshold = job.settings.dirtyFlushThreshold() > 0
                    && new SnapshotDirtyRoots(redis, job.prefix, job.settings).pendingCount() >= job.settings.dirtyFlushThreshold();
            if (interval || threshold) { flushDirty(job.plan.name()); job.lastDirtyFlushMillis = now; }
        } catch (RuntimeException failure) {
            job.forceFull.set(true);
            LOG.warn("Auto-dirty execution failed; reconciliation remains due: plan={}", job.plan.name());
        } finally { job.dirtyBusy.set(false); }
    }

    public SnapshotJobs withSchedulingEnabled(boolean enabled) {
        if (running)
            throw new IllegalStateException("Configure scheduling before starting snapshot jobs");
        schedulingEnabled = enabled;
        return this;
    }

    public SnapshotJobs(
            DataSource source,
            JedisPooled redis,
            ObjectMapper mapper,
            Clock clock,
            String namespace,
            List<SnapshotPlan<?, ?>> plans,
            Map<String, SnapshotSettings> settings) {
        this(source, JedisPooledOperations.of(redis), mapper, clock, namespace, plans, settings);
    }

    public SnapshotJobs(
            DataSource source,
            RedisClientOperations redis,
            ObjectMapper mapper,
            Clock clock,
            String namespace,
            List<SnapshotPlan<?, ?>> plans,
            Map<String, SnapshotSettings> settings) {
        this(source, redis, redis, mapper, clock, namespace, plans, settings);
    }

    public SnapshotJobs(
            DataSource source,
            JedisPooled redis,
            JedisPooled readRedis,
            ObjectMapper mapper,
            Clock clock,
            String namespace,
            List<SnapshotPlan<?, ?>> plans,
            Map<String, SnapshotSettings> settings) {
        this(
                source,
                JedisPooledOperations.of(redis),
                JedisPooledOperations.of(readRedis),
                mapper,
                clock,
                namespace,
                plans,
                settings);
    }

    public SnapshotJobs(
            DataSource source,
            RedisClientOperations redis,
            RedisClientOperations readRedis,
            ObjectMapper mapper,
            Clock clock,
            String namespace,
            List<SnapshotPlan<?, ?>> plans,
            Map<String, SnapshotSettings> settings) {
        this(source, redis, readRedis, mapper, clock, namespace, plans, settings, false);
    }

    /** No mapper is created or required. Every plan must explicitly declare its JSON codec. */
    public static SnapshotJobs jacksonFree(
            DataSource source, RedisClientOperations redis, RedisClientOperations readRedis,
            Clock clock, String namespace, List<SnapshotPlan<?, ?>> plans, Map<String, SnapshotSettings> settings) {
        return new SnapshotJobs(source, redis, readRedis, null, clock, namespace, plans, settings, true);
    }

    private SnapshotJobs(
            DataSource source, RedisClientOperations redis, RedisClientOperations readRedis,
            ObjectMapper mapper, Clock clock, String namespace, List<SnapshotPlan<?, ?>> plans,
            Map<String, SnapshotSettings> settings, boolean jacksonFree) {
        this.dataSource = Objects.requireNonNull(source);
        this.redis = Objects.requireNonNull(redis);
        this.readRedis = Objects.requireNonNull(readRedis);
        this.mapper = jacksonFree ? null : Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        if (namespace == null || !namespace.matches("[A-Za-z0-9:_{}-]{1,210}"))
            throw new IllegalArgumentException("Invalid snapshot namespace");
        if (namespace.contains("{"))
            com.reactor.cachedb.redis.ClusterHashTagKeyStrategy.validateTagged(namespace);
        for (var plan : plans) {
            if (jacksonFree && plan.jsonCodec() == null)
                throw new IllegalArgumentException("Jackson-free snapshot plan requires withJsonCodec: " + plan.name());
            Job job =
                    new Job(
                            plan,
                            settings.getOrDefault(plan.name(), SnapshotSettings.defaults()),
                            snapshotPrefix(namespace, plan.name()));
            if (jobs.putIfAbsent(plan.name(), job) != null)
                throw new IllegalArgumentException("Duplicate snapshot plan");
        }
    }

    private synchronized ScheduledExecutorService heartbeat() {
        if (closed) throw new IllegalStateException("Snapshot jobs are closed");
        if (heartbeat == null) heartbeat = Executors.newScheduledThreadPool(
                Math.max(1, Math.min(4, jobs.size())), task -> {
                    Thread thread = new Thread(task, "cachedb-snapshot-lease");
                    thread.setDaemon(true);
                    return thread;
                });
        return heartbeat;
    }

    public SnapshotRepository repository(String name) {
        Job job = job(name);
        return new RedisSnapshotRepository(
                readRedis,
                job.prefix,
                job.plan.indexSpec() != null,
                ids -> refreshRoots(name, ids),
                job.settings.batchTargetSize().toBytes(),
                clock,
                job.settings.warnAge());
    }

    static String snapshotPrefix(String namespace, String name) {
        // The first tag selects the slot. Never let a preceding application tag win.
        return namespace.contains("{")
                ? namespace.replace("{", "").replace("}", "")
                        + ":{snapshot:"
                        + Base64.getUrlEncoder().withoutPadding().encodeToString(namespace.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        + ":"
                        + name
                        + "}"
                : namespace + ":{snapshot:" + name + "}";
    }

    public <V> TypedSnapshotRepository<String, V> typedRepository(String name) {
        return typedRepository(name, SnapshotIdentity.INSTANCE);
    }

    public <ID, V> TypedSnapshotRepository<ID, V> typedRepository(
            String name, java.util.function.Function<ID, String> idMapper) {
        Job job = job(name);
        @SuppressWarnings("unchecked") JsonArrayCodec<V> codec = (JsonArrayCodec<V>) job.codec(mapper);
        if (codec instanceof JacksonJsonArrayCodec<?> legacy) legacy.prepared();
        return new DecodedSnapshotRepository<>(repository(name), codec, idMapper);
    }

    public <V> TypedSnapshotRepository<String, V> typedRepository(String name, Class<V> valueType) {
        if (!job(name).plan.valueType().equals(valueType))
            throw new IllegalArgumentException("Snapshot value type does not match plan");
        return typedRepository(name);
    }

    public <ID, V> TypedSnapshotRepository<ID, V> typedRepository(
            String name, Class<V> valueType, java.util.function.Function<ID, String> idMapper) {
        if (!job(name).plan.valueType().equals(valueType))
            throw new IllegalArgumentException("Snapshot value type does not match plan");
        return typedRepository(name, idMapper);
    }

    public SnapshotSettings settings(String name) {
        return job(name).settings;
    }

    public SnapshotRefreshResult refresh(String name, boolean manual) {
        return refresh(name, manual, List.of());
    }

    @Override
    public SnapshotRefreshResult refreshRoots(String name, Collection<String> rootIds) {
        Job job = job(name);
        if (job.plan.rootScope() == null)
            throw new IllegalStateException("Partial refresh requires an explicit root scope");
        job.plan.rootScope().validate(job.plan);
        List<String> ids = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(rootIds)));
        if (ids.isEmpty() || ids.size() > job.settings.batchRows())
            throw new IllegalArgumentException("Partial refresh root count must be 1..batchRows");
        for (String id : ids)
            if (id.isBlank() || id.length() > 512 || id.indexOf('\n') >= 0)
                throw new IllegalArgumentException("Invalid partial refresh root id");
        return refresh(name, true, ids);
    }

    private SnapshotRefreshResult refresh(String name, boolean manual, List<String> rootIds) {
        Job job = job(name);
        synchronized (job) {
            if (closed) throw new IllegalStateException("Snapshot jobs are closed");
            if (!job.busy.compareAndSet(false, true)) return SnapshotRefreshResult.skipped("BUSY");
            job.worker = Thread.currentThread();
        }
        String owner = UUID.randomUUID().toString();
        String generation = UUID.randomUUID().toString();
        String lock = job.prefix + ":lock";
        boolean acquired = false;
        long started = System.nanoTime();
        try {
            if (!manual && !due(job)) return SnapshotRefreshResult.skipped("NOT_DUE");
            acquired =
                    "OK"
                            .equals(
                                    redis.commands()
                                            .set(
                                                    lock,
                                                    owner,
                                                    SetParams.setParams()
                                                            .nx()
                                                            .px(
                                                                    job.settings
                                                                            .leaseDuration()
                                                                            .toMillis())));
            if (!acquired) return SnapshotRefreshResult.skipped("BUSY");
            if (!manual && !due(job)) return SnapshotRefreshResult.skipped("NOT_DUE");
            return execute(job, lock, owner, generation, started, rootIds);
        } catch (RuntimeException failure) {
            LOG.error("Snapshot refresh failed: job={}", name, failure);
            throw failure;
        } finally {
            if (acquired) {
                // An uncertain commit reply must not cause deletion of the now-active generation.
                clean(job, generation);
                try {
                    redis.eval(RELEASE, List.of(lock), List.of(owner));
                } catch (RuntimeException failure) {
                    LOG.warn("Snapshot lease release failed: job={}", name);
                }
            }
            synchronized (job) {
                job.worker = null;
                job.busy.set(false);
            }
        }
    }

    private SnapshotRefreshResult execute(
            Job job,
            String lock,
            String owner,
            String generation,
            long started,
            List<String> rootIds) {
        AtomicBoolean lost = new AtomicBoolean();
        Thread worker = Thread.currentThread();
        Object guard = new Object();
        AtomicBoolean active = new AtomicBoolean(true);
        long renewMillis = Math.max(250, job.settings.leaseDuration().toMillis() / 3);
        var renewal =
                heartbeat().scheduleAtFixedRate(
                        () -> {
                            boolean failed;
                            try {
                                failed =
                                        closed
                                                || System.nanoTime() - started
                                                        >= job.settings.timeout().toNanos()
                                                || !Long.valueOf(1)
                                                        .equals(
                                                                redis.eval(
                                                                        RENEW,
                                                                        List.of(lock),
                                                                        List.of(
                                                                                owner,
                                                                                Long.toString(
                                                                                        job.settings
                                                                                                .leaseDuration()
                                                                                                .toMillis()))));
                            } catch (RuntimeException failure) {
                                failed = true;
                            }
                            synchronized (guard) {
                                if (active.get() && failed) {
                                    lost.set(true);
                                    worker.interrupt();
                                }
                            }
                        },
                        renewMillis,
                        renewMillis,
                        TimeUnit.MILLISECONDS);
        Runnable check =
                () -> {
                    if (closed
                            || lost.get()
                            || Thread.currentThread().isInterrupted()
                            || System.nanoTime() - started >= job.settings.timeout().toNanos())
                        throw new IllegalStateException(
                                "Snapshot lease lost or execution timed out");
                };
        long sourceAt = clock.millis();
        try (var spool = new SnapshotSpool(job.settings.directory())) {
            String expectedActive =
                    rootIds.isEmpty() ? null : redis.commands().get(job.prefix + ":active");
            if (!rootIds.isEmpty() && expectedActive == null)
                throw new IllegalStateException(
                        "First complete snapshot is required before partial refresh");
            Runnable prepareCheck =
                    () -> {
                        check.run();
                        if (System.nanoTime() - started
                                >= job.settings.preparationTimeout().toNanos())
                            throw new IllegalStateException("Snapshot preparation timed out");
                    };
            prepareTyped(job.plan, job.settings, spool, prepareCheck, rootIds);
            if (!rootIds.isEmpty()) {
                if (spool.bytes() > job.settings.batchTargetSize().toBytes())
                    throw new IllegalStateException(
                            "Partial refresh exceeds atomic batch byte budget");
                var delta =
                        SnapshotDelta.batch(
                                job.prefix,
                                lock,
                                owner,
                                expectedActive,
                                sourceAt,
                                job.plan.contentReuse(),
                                rootIds,
                                spool,
                                check,
                                availableContent(job, spool.entries()));
                check.run();
                Object applied = redis.eval(SnapshotDelta.APPLY, delta.keys(), delta.arguments());
                if (Long.valueOf(-1L).equals(applied)) {
                    check.run();
                    delta = SnapshotDelta.batch(job.prefix, lock, owner, expectedActive, sourceAt,
                            job.plan.contentReuse(), rootIds, spool, check);
                    applied = redis.eval(SnapshotDelta.APPLY, delta.keys(), delta.arguments());
                }
                if (!Long.valueOf(rootIds.size()).equals(applied))
                    throw new IllegalStateException("Partial refresh ownership or generation lost");
                return new SnapshotRefreshResult(
                        "COMPLETED",
                        rootIds.size(),
                        spool.entries().size(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            int submitted = publish(job, spool, lock, owner, generation, check);
            check.run();
            var reply =
                    (List<?>)
                            redis.eval(
                                    COMMIT,
                                    List.of(
                                            lock,
                                            job.prefix + ":data:" + generation,
                                            job.prefix + ":active",
                                            job.prefix + ":completed-at",
                                            job.prefix + ":meta:" + generation),
                                    List.of(
                                            owner,
                                            generation + ":" + sourceAt,
                                            Long.toString(job.settings.retention().toMillis()),
                                            Long.toString(clock.millis()),
                                            Integer.toString(spool.entries().size()),
                                            job.plan.indexSpec() == null ? "0" : "1"));
            if (!Long.valueOf(1).equals(reply.get(0)))
                throw new IllegalStateException("Snapshot publication ownership lost");
            String previous = (String) reply.get(1);
            if (!previous.isEmpty()) clean(job, previous.substring(0, previous.indexOf(':')));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            LOG.info(
                    "Snapshot committed: job={} rows={} bytes={} durationMs={}",
                    job.plan.name(),
                    submitted,
                    spool.bytes(),
                    millis);
            return new SnapshotRefreshResult(
                    "COMPLETED", spool.entries().size(), submitted, millis);
        } catch (IOException failure) {
            throw new UncheckedIOException("Snapshot temporary storage failed", failure);
        } finally {
            synchronized (guard) {
                active.set(false);
            }
            renewal.cancel(false);
            if (lost.get()) Thread.interrupted();
        }
    }

    private <R, V> void prepareTyped(
            SnapshotPlan<R, V> plan,
            SnapshotSettings settings,
            SnapshotSpool spool,
            Runnable check,
            List<String> rootIds)
            throws IOException {
        if (plan.streaming())
            spool.prepareStreaming(dataSource, plan, settings, codec(plan), check, rootIds);
        else {
            SnapshotRows rows = SnapshotJdbcReader.read(dataSource, plan, settings, check, rootIds);
            spool.prepare(plan, rows, codec(plan), check);
        }
    }

    @SuppressWarnings("unchecked")
    private <R, V> JsonArrayCodec<V> codec(SnapshotPlan<R, V> plan) {
        return (JsonArrayCodec<V>) job(plan.name()).codec(mapper);
    }

    private void prepare(Job job, SnapshotSpool spool, Runnable check) throws IOException {
        prepareTyped(job.plan, job.settings, spool, check, List.of());
    }

    private int publish(
            Job job,
            SnapshotSpool spool,
            String lock,
            String owner,
            String generation,
            Runnable check)
            throws IOException {
        int submitted = 0;
        long warningCount = 0;
        long payloadWarningBytes = job.settings.payloadWarningSize().toBytes();
        if (payloadWarningBytes > 0)
            for (var entry : spool.entries())
                if (entry.bytes() > payloadWarningBytes) warningCount++;
        if (warningCount > 0
                || (job.settings.catalogWarningSize().toBytes() > 0
                        && spool.bytes() > job.settings.catalogWarningSize().toBytes()))
            LOG.warn(
                    "Large snapshot: job={} bytes={} largeRows={}; no rows truncated",
                    job.plan.name(),
                    spool.bytes(),
                    warningCount);
        while (submitted < spool.entries().size()) {
            check.run();
            int count = 0;
            long bytes = 0;
            while (submitted + count < spool.entries().size() && count < job.settings.batchRows()) {
                var entry = spool.entries().get(submitted + count);
                if (count > 0 && entry.bytes() > job.settings.batchTargetSize().toBytes() - bytes)
                    break;
                bytes += entry.bytes();
                count++;
                if (bytes >= job.settings.batchTargetSize().toBytes()) break;
            }
            check.run();
            Object accepted = stage(job, spool, lock, owner, generation, submitted, count, check);
            if (!Long.valueOf(count).equals(accepted))
                throw new IllegalStateException("Snapshot stage ownership lost");
            submitted += count;
        }
        return submitted;
    }

    private boolean due(Job job) {
        String completed = redis.commands().get(job.prefix + ":completed-at");
        if (completed == null) return true;
        long age = clock.millis() - Long.parseLong(completed);
        return age < 0 || age >= job.settings.interval().toMillis();
    }

    private Object stage(
            Job job,
            SnapshotSpool spool,
            String lock,
            String owner,
            String generation,
            int start,
            int count,
            Runnable check) {
        return stage(job, spool, lock, owner, generation, start, count, check,
                availableContent(job, spool.entries().subList(start, start + count)));
    }

    private Set<String> availableContent(Job job, List<SnapshotSpool.Entry> entries) {
        if (!job.plan.contentReuse()) return Set.of();
        Map<String, redis.clients.jedis.Response<Boolean>> responses = new LinkedHashMap<>();
        try (var pipeline = redis.pipeline()) {
            for (var entry : entries) {
                String digest = entry.contentDigest();
                if (digest != null && !responses.containsKey(digest))
                    responses.put(digest, pipeline.exists(job.prefix + ":content:" + digest));
            }
            pipeline.sync();
        }
        Set<String> available = new HashSet<>();
        responses.forEach((digest, response) -> { if (Boolean.TRUE.equals(response.get())) available.add(digest); });
        return available;
    }

    private Object stage(Job job, SnapshotSpool spool, String lock, String owner, String generation,
            int start, int count, Runnable check, Set<String> available) {
        var batch =
                SnapshotPublication.batch(
                        job.prefix,
                        generation,
                        lock,
                        owner,
                        Math.addExact(
                                job.settings.retention().toMillis(),
                                job.settings.timeout().toMillis()),
                        job.plan.contentReuse(),
                        spool.entries().subList(start, start + count),
                        entry -> {
                            try {
                                return spool.read(entry, check);
                            } catch (IOException failure) {
                                throw new UncheckedIOException(failure);
                            }
                        }, available);
        check.run();
        Object result = redis.eval(SnapshotPublication.STAGE, batch.keys(), batch.arguments());
        if (Long.valueOf(-1L).equals(result) && !available.isEmpty()) {
            return stage(job, spool, lock, owner, generation, start, count, check, Set.of());
        }
        return result;
    }

    private void clean(Job job, String generation) {
        try {
            String indexCursor = "0";
            do {
                var indexes =
                        redis.commands()
                                .sscan(
                                        job.prefix + ":keys:" + generation,
                                        indexCursor,
                                        new redis.clients.jedis.params.ScanParams().count(200));
                indexCursor = indexes.getCursor();
                if (!indexes.getResult().isEmpty()) {
                    var keys = new ArrayList<String>();
                    keys.add(job.prefix + ":active");
                    keys.addAll(indexes.getResult());
                    redis.eval(
                            "local active=redis.call('GET',KEYS[1]) or ''; if"
                                + " string.sub(active,1,string.len(ARGV[1])+1) == ARGV[1]..':' then"
                                + " return 0 end; for i=2,#KEYS do redis.call('UNLINK',KEYS[i])"
                                + " end; return 1",
                            keys,
                            List.of(generation));
                }
            } while (!indexCursor.equals("0"));
            if ("content".equals(redis.commands().get(job.prefix + ":format:" + generation))) {
                String cursor = "0";
                do {
                    var result =
                            redis.commands()
                                    .sscan(
                                            job.prefix + ":digests:" + generation,
                                            cursor,
                                            new redis.clients.jedis.params.ScanParams().count(200));
                    cursor = result.getCursor();
                    List<String> args = new ArrayList<>(List.of(job.prefix, generation));
                    args.addAll(result.getResult());
                    redis.eval(
                            CLEAN_CONTENT,
                            List.of(job.prefix + ":active", job.prefix + ":staging"),
                            args);
                } while (!cursor.equals("0"));
            }
            redis.eval(
                    CLEAN,
                    List.of(
                            job.prefix + ":active",
                            job.prefix + ":data:" + generation,
                            job.prefix + ":keys:" + generation,
                            job.prefix + ":meta:" + generation,
                            job.prefix + ":format:" + generation,
                            job.prefix + ":times:" + generation,
                            job.prefix + ":revision:" + generation,
                            job.prefix + ":digests:" + generation,
                            job.prefix + ":partition-revisions:" + generation),
                    List.of(generation));
        } catch (RuntimeException failure) {
            LOG.warn("Snapshot cleanup deferred to TTL: job={}", job.plan.name());
        }
    }

    private Job job(String name) {
        Job result = jobs.get(name);
        if (result == null) throw new IllegalArgumentException("Unknown snapshot job: " + name);
        return result;
    }

    public int markDirty(String name, Collection<?> rootIds) {
        Job job = job(name);
        if (job.plan.rootScope() == null) throw new IllegalStateException("Dirty roots require an explicit root scope");
        return new SnapshotDirtyRoots(redis, job.prefix, job.settings).mark(rootIds);
    }

    public Optional<SnapshotManifest> flushDirty(String name) {
        Job job = job(name);
        if (job.plan.rootScope() == null) throw new IllegalStateException("Dirty roots require an explicit root scope");
        return new SnapshotDirtyRoots(redis, job.prefix, job.settings).flush(name, ids -> refreshRoots(name, ids));
    }

    @Override
    public synchronized void start() {
        if (!schedulingEnabled) return;
        if (changeDatabase == null && jobs.values().stream().anyMatch(job -> !job.plan.autoDirtyBindings().isEmpty()))
            throw new IllegalStateException("Auto-dirty plans require withDatabase or Spring auto-configuration");
        if (closed) throw new IllegalStateException("Snapshot jobs are closed");
        if (running) return;
        if (jobs.values().stream().anyMatch(job -> job.settings.enabled()))
            scheduler = Executors.newScheduledThreadPool(Math.max(1, Math.min(4, jobs.size())), task -> {
                Thread thread = new Thread(task, "cachedb-snapshot-schedule");
                thread.setDaemon(true);
                return thread;
            });
        running = true;
        for (Job job : jobs.values())
            if (job.settings.enabled()) {
                schedules.add(
                        scheduler.scheduleWithFixedDelay(
                                () -> {
                                    boolean forced = job.forceFull.getAndSet(false);
                                    try {
                                        var result = refresh(job.plan.name(), forced);
                                        if (forced && !"COMPLETED".equals(result.status())) job.forceFull.set(true);
                                    } catch (RuntimeException ignored) {
                                        if (forced) job.forceFull.set(true);
                                    }
                                },
                                0,
                                job.settings.interval().toMillis(),
                                TimeUnit.MILLISECONDS));
                if (!job.plan.autoDirtyBindings().isEmpty() || !job.settings.dirtyFlushInterval().isZero()
                        || job.settings.dirtyFlushThreshold() > 0)
                    schedules.add(scheduler.scheduleWithFixedDelay(() -> dirtyTick(job), 250, 250, TimeUnit.MILLISECONDS));
            }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public void stop() {
        close();
    }

    @Override
    public synchronized void close() {
        closed = true;
        running = false;
        if (changeRegistration != null) try { changeRegistration.close(); }
        catch (Exception failure) { LOG.warn("Snapshot dirty bridge close failed"); }
        for (Job job : jobs.values())
            synchronized (job) {
                if (job.worker != null) job.worker.interrupt();
            }
        for (var future : schedules) future.cancel(true);
        if (scheduler != null) scheduler.shutdownNow();
        if (heartbeat != null) heartbeat.shutdownNow();
    }

    private static final class Job {
        final Set<String> changedRoots = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicInteger queuedRoots = new java.util.concurrent.atomic.AtomicInteger();
        final AtomicBoolean forceFull = new AtomicBoolean();
        final AtomicBoolean dirtyBusy = new AtomicBoolean();
        long lastDirtyFlushMillis;
        final SnapshotPlan<?, ?> plan;
        final SnapshotSettings settings;
        final String prefix;
        final AtomicBoolean busy = new AtomicBoolean();
        Thread worker;
        private volatile JsonArrayCodec<?> codec;

        JsonArrayCodec<?> codec(ObjectMapper mapper) {
            var value = codec;
            if (value == null)
                synchronized (this) {
                    value = codec;
                    if (value == null) codec = value = plan.jsonCodec() != null ? plan.jsonCodec() : new JacksonJsonArrayCodec<>(mapper, plan.valueType());
                }
            return value;
        }

        Job(SnapshotPlan<?, ?> plan, SnapshotSettings settings, String prefix) {
            this.plan = plan;
            this.settings = settings;
            this.prefix = prefix;
        }
    }
}

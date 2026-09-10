package com.reactor.cachedb.spring.boot.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.SetParams;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

/** Framework-owned bounded snapshot preparation, coordination and generation publication. */
public final class SnapshotJobs implements SnapshotOperations, SmartLifecycle, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotJobs.class);
    private static final String RENEW =
            "if redis.call('GET',KEYS[1]) == ARGV[1] then return"
                    + " redis.call('PEXPIRE',KEYS[1],ARGV[2]) end return 0";
    private static final String RELEASE =
            "if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) end"
                    + " return 0";
    private static final String STAGE =
            """
            if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end
            for i=3,#ARGV,2 do redis.call('HSET',KEYS[2],ARGV[i],ARGV[i+1]) end
            redis.call('PEXPIRE',KEYS[2],ARGV[2])
            return (#ARGV-2)/2
            """;
    private static final String COMMIT =
            """
            if redis.call('GET',KEYS[1]) ~= ARGV[1] then return {0,''} end
            if redis.call('HLEN',KEYS[2]) ~= tonumber(ARGV[5]) then return {0,''} end
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
              return redis.call('UNLINK',KEYS[2])
            end
            return 0
            """;
    private final DataSource dataSource;
    private final JedisPooled redis;
    private final JedisPooled readRedis;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService heartbeat;
    private final List<ScheduledFuture<?>> schedules = new ArrayList<>();
    private volatile boolean running;
    private volatile boolean closed;

    public SnapshotJobs(
            DataSource source,
            JedisPooled redis,
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
        this.dataSource = Objects.requireNonNull(source);
        this.redis = Objects.requireNonNull(redis);
        this.readRedis = Objects.requireNonNull(readRedis);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        if (namespace == null || !namespace.matches("[A-Za-z0-9:_-]{1,100}"))
            throw new IllegalArgumentException("Invalid snapshot namespace");
        for (var plan : plans) {
            Job job =
                    new Job(
                            plan,
                            settings.getOrDefault(plan.name(), SnapshotSettings.defaults()),
                            namespace + ":{snapshot:" + plan.name() + "}");
            if (jobs.putIfAbsent(plan.name(), job) != null)
                throw new IllegalArgumentException("Duplicate snapshot plan");
        }
        scheduler =
                Executors.newScheduledThreadPool(
                        Math.max(1, Math.min(4, plans.size())),
                        task -> new Thread(task, "cachedb-snapshot-schedule"));
        heartbeat =
                Executors.newScheduledThreadPool(
                        Math.max(1, Math.min(4, plans.size())),
                        task -> {
                            Thread thread = new Thread(task, "cachedb-snapshot-lease");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    public SnapshotRepository repository(String name) {
        return new RedisSnapshotRepository(readRedis, job(name).prefix);
    }

    public SnapshotSettings settings(String name) {
        return job(name).settings;
    }

    public SnapshotRefreshResult refresh(String name, boolean manual) {
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
                                    redis.set(
                                            lock,
                                            owner,
                                            SetParams.setParams()
                                                    .nx()
                                                    .px(job.settings.leaseDuration().toMillis())));
            if (!acquired) return SnapshotRefreshResult.skipped("BUSY");
            if (!manual && !due(job)) return SnapshotRefreshResult.skipped("NOT_DUE");
            return execute(job, lock, owner, generation, started);
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
            Job job, String lock, String owner, String generation, long started) {
        AtomicBoolean lost = new AtomicBoolean();
        Thread worker = Thread.currentThread();
        Object guard = new Object();
        AtomicBoolean active = new AtomicBoolean(true);
        long renewMillis = Math.max(250, job.settings.leaseDuration().toMillis() / 3);
        var renewal =
                heartbeat.scheduleAtFixedRate(
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
            Runnable prepareCheck =
                    () -> {
                        check.run();
                        if (System.nanoTime() - started
                                >= job.settings.preparationTimeout().toNanos())
                            throw new IllegalStateException("Snapshot preparation timed out");
                    };
            prepare(job, spool, prepareCheck);
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
                                            job.prefix + ":completed-at"),
                                    List.of(
                                            owner,
                                            generation + ":" + sourceAt,
                                            Long.toString(job.settings.retention().toMillis()),
                                            Long.toString(clock.millis()),
                                            Integer.toString(spool.entries().size())));
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
            SnapshotPlan<R, V> plan, SnapshotSettings settings, SnapshotSpool spool, Runnable check)
            throws IOException {
        SnapshotRows rows = SnapshotJdbcReader.read(dataSource, plan, settings, check);
        spool.prepare(plan, rows, mapper, check);
    }

    private void prepare(Job job, SnapshotSpool spool, Runnable check) throws IOException {
        prepareTyped(job.plan, job.settings, spool, check);
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
        long warningCount =
                spool.entries().stream()
                        .filter(
                                e ->
                                        job.settings.payloadWarningSize().toBytes() > 0
                                                && e.bytes()
                                                        > job.settings
                                                                .payloadWarningSize()
                                                                .toBytes())
                        .count();
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
            List<String> args = new ArrayList<>();
            args.add(owner);
            args.add(Long.toString(job.settings.retention().toMillis()));
            int count = 0;
            long bytes = 0;
            while (submitted + count < spool.entries().size() && count < job.settings.batchRows()) {
                var entry = spool.entries().get(submitted + count);
                if (count > 0 && entry.bytes() > job.settings.batchTargetSize().toBytes() - bytes)
                    break;
                args.add(entry.id());
                args.add(spool.read(entry, check));
                bytes += entry.bytes();
                count++;
                if (bytes >= job.settings.batchTargetSize().toBytes()) break;
            }
            check.run();
            Object accepted =
                    redis.eval(STAGE, List.of(lock, job.prefix + ":data:" + generation), args);
            if (!Long.valueOf(count).equals(accepted))
                throw new IllegalStateException("Snapshot stage ownership lost");
            submitted += count;
        }
        return submitted;
    }

    private boolean due(Job job) {
        String completed = redis.get(job.prefix + ":completed-at");
        if (completed == null) return true;
        long age = clock.millis() - Long.parseLong(completed);
        return age < 0 || age >= job.settings.interval().toMillis();
    }

    private void clean(Job job, String generation) {
        try {
            redis.eval(
                    CLEAN,
                    List.of(job.prefix + ":active", job.prefix + ":data:" + generation),
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

    @Override
    public synchronized void start() {
        if (closed) throw new IllegalStateException("Snapshot jobs are closed");
        if (running) return;
        running = true;
        for (Job job : jobs.values())
            if (job.settings.enabled()) {
                schedules.add(
                        scheduler.scheduleWithFixedDelay(
                                () -> {
                                    try {
                                        refresh(job.plan.name(), false);
                                    } catch (RuntimeException ignored) {
                                        /* Already logged. */
                                    }
                                },
                                0,
                                job.settings.interval().toMillis(),
                                TimeUnit.MILLISECONDS));
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
        for (Job job : jobs.values())
            synchronized (job) {
                if (job.worker != null) job.worker.interrupt();
            }
        for (var future : schedules) future.cancel(true);
        scheduler.shutdownNow();
        heartbeat.shutdownNow();
    }

    private static final class Job {
        final SnapshotPlan<?, ?> plan;
        final SnapshotSettings settings;
        final String prefix;
        final AtomicBoolean busy = new AtomicBoolean();
        Thread worker;

        Job(SnapshotPlan<?, ?> plan, SnapshotSettings settings, String prefix) {
            this.plan = plan;
            this.settings = settings;
            this.prefix = prefix;
        }
    }
}

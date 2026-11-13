package com.reactor.cachedb.redis;

import com.reactor.cachedb.core.config.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import redis.clients.jedis.Connection;
import redis.clients.jedis.Protocol.Command;

/** Bounded, per-runtime connection lifecycle. No control I/O on a caller or under a monitor. */
final class RedisTrackingCoordinator implements AutoCloseable {
    private static final String INVALIDATE = "__redis__:invalidate";
    private final RedisClientOperations redis;
    private final RedisKeyStrategy keys;
    private final NearCacheInvalidationConfig config;
    private final Supplier<Map<String, RedisNearCacheCoordinator.Store>> stores;
    private final Runnable clearStores;
    private final ReentrantLock gate = new ReentrantLock();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicBoolean warned = new AtomicBoolean(), cleanupWarned = new AtomicBoolean();
    private final AtomicLong invalidations = new AtomicLong(), flushes = new AtomicLong(), reconnects = new AtomicLong();
    private volatile boolean closed, cleanupFailed;
    private volatile long readyUntil;
    private volatile NearCacheInvalidationMode mode = NearCacheInvalidationMode.PUBSUB;
    private volatile Epoch active, building, retired;
    private volatile Thread runner;
    private volatile long closeUntil;
    private volatile String lastFailure = "";
    private boolean everTracked;

    RedisTrackingCoordinator(RedisClientOperations redis, RedisKeyStrategy keys, NearCacheInvalidationConfig config,
            Supplier<Map<String, RedisNearCacheCoordinator.Store>> stores, Runnable clearStores) {
        this.redis = redis; this.keys = keys; this.config = config; this.stores = stores; this.clearStores = clearStores;
    }
    boolean ready() { return ready(false); }
    private boolean ready(boolean trackingRequired) {
        Epoch epoch = active;
        long until = readyUntil;
        boolean healthy = !closed && !cleanupFailed && epoch != null && !epoch.failed.get()
                && epoch.revision == revision.get() && until != 0 && until - System.nanoTime() > 0 && epoch == active;
        if (healthy) return !trackingRequired || epoch.tracking;
        if (until != 0) down(epoch);
        return false;
    }
    NearCacheInvalidationMode mode() { return mode; }
    boolean trackingReady() { return ready(true); }
    boolean publishLegacy() { return config.publishLegacyInvalidations() || !trackingReady(); }
    long invalidations() { return invalidations.get(); }
    long flushes() { return flushes.get(); }
    long reconnects() { return reconnects.get(); }
    String lastFailure() { return lastFailure; }
    Map<String, List<Long>> clientIds() {
        Epoch epoch = active;
        if (epoch == null || !epoch.tracking || !trackingReady()) return Map.of();
        var ids = new TreeMap<String, List<Long>>();
        for (Session session : epoch.sessions)
            ids.put(session.serverId, List.of(session.subscriptionId, session.controlId));
        return epoch == active && trackingReady() ? Map.copyOf(ids) : Map.of();
    }
    void changed() {
        revision.incrementAndGet();
        down(null);
        // Deliberately do not interrupt an in-progress connection/registration.
        // The supervisor observes revision and retires that generation before creating another.
    }
    private void clearAll() { flushes.incrementAndGet(); clearStores.run(); }
    private void down(Epoch epoch) {
        if (epoch != null) epoch.failed.set(true);
        gate.lock();
        try {
            if (epoch == null || active == epoch && readyUntil != 0) {
                readyUntil = 0;
                clearAll(); // Never publish readiness before the clear completes.
            }
        } finally { gate.unlock(); }
    }
    private void warnFallback() {
        if (warned.compareAndSet(false, true))
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                    "Server tracking unavailable on every target node; using PUBSUB when healthy. L1 remains disabled until ready.");
    }
    private void closeOwned(RedisDedicatedConnection connection) {
        if (connection == null) return;
        try { connection.close(); }
        catch (RuntimeException failure) { failCleanup(); }
    }
    private void failCleanup() {
        cleanupFailed = true;
        lastFailure = "DEDICATED_CONNECTION_CLEANUP_FAILED";
        down(null);
        if (cleanupWarned.compareAndSet(false, true))
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                    "Dedicated invalidation connection cleanup failed; readiness is disabled");
    }
    private void rememberFailure(RuntimeException failure) {
        if (failure instanceof RedisDedicatedConnection.CleanupFailure) failCleanup();
        else if (!closed && !cleanupFailed) lastFailure = failure.getClass().getSimpleName();
    }

    void run() {
        gate.lock();
        try {
            if (closed || cleanupFailed || runner != null) return;
            if (!Thread.currentThread().isVirtual())
                throw new IllegalStateException("Invalidation I/O requires a virtual-thread owner");
            runner = Thread.currentThread();
        } finally { gate.unlock(); }
        int failures = 0, delay = config.initialBackoffMillis();
        try {
            while (!closed && !cleanupFailed) {
                Epoch epoch = null;
                long observed = revision.get();
                try {
                    Epoch old = retired;
                    if (old != null && !old.quiescent()) { pause(config.heartbeatMillis()); continue; }
                    retired = null;
                    if (!(redis instanceof RedisTrackingConnectionProvider provider)) {
                        warnFallback();
                        lastFailure = "UNSUPPORTED_DEDICATED_CONNECTION_PROVIDER";
                        pause(config.retryCooldownMillis());
                        continue;
                    }
                    if (config.mode() != NearCacheInvalidationMode.PUBSUB) {
                        try { epoch = build(provider, observed, true); }
                        catch (RuntimeException failure) {
                            rememberFailure(failure);
                            if (config.mode() != NearCacheInvalidationMode.AUTO || retired != null || cleanupFailed) throw failure;
                            warnFallback();
                        }
                    }
                    if (epoch == null) epoch = build(provider, observed, false);
                    if (install(epoch)) {
                        failures = 0; delay = config.initialBackoffMillis();
                        supervise(provider, epoch);
                    }
                } catch (InterruptedException stopped) {
                    if (closed) break;
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException failure) {
                    rememberFailure(failure);
                    if (!closed && !cleanupFailed && config.mode() == NearCacheInvalidationMode.AUTO) warnFallback();
                } finally {
                    if (epoch != null) retire(epoch);
                }
                if (closed || cleanupFailed) break;
                if (observed != revision.get()) continue;
                if (++failures >= config.maxAttempts()) {
                    pause(config.retryCooldownMillis()); failures = 0; delay = config.initialBackoffMillis();
                } else {
                    pause(delay); delay = Math.min(config.maxBackoffMillis(), delay * 2);
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            down(null);
            Epoch pending = building;
            if (pending != null) pending.stop();
            gate.lock();
            try { runner = null; } finally { gate.unlock(); }
        }
    }
    private List<String> prefixes(Epoch epoch) {
        Map<String, RedisNearCacheCoordinator.Store> snapshot = epoch.storeSnapshot;
        if (snapshot.isEmpty() || snapshot.size() > config.maxNamespaces())
            throw new IllegalStateException("Tracking namespace budget exceeded");
        epoch.prefixSnapshot = keys.entityKeyPrefixes(snapshot.keySet());
        if (!epoch.prefixSnapshot.keySet().equals(snapshot.keySet()))
            throw new IllegalStateException("Tracking prefix mapping does not cover the registered stores");
        var prefixes = new ArrayList<String>(snapshot.size());
        long bytes = 0;
        for (String prefix : epoch.prefixSnapshot.values()) {
            if (prefix.isEmpty() || prefix.length() > 4096) throw new IllegalStateException("Invalid tracking prefix");
            bytes += prefix.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 1048576) throw new IllegalStateException("Tracking prefix bytes exceed 1 MiB");
            prefixes.add(prefix);
        }
        Collections.sort(prefixes);
        for (int i = 1; i < prefixes.size(); i++)
            if (prefixes.get(i).startsWith(prefixes.get(i - 1)))
                throw new IllegalStateException("Overlapping tracking prefixes");
        return List.copyOf(prefixes);
    }
    private Epoch build(RedisTrackingConnectionProvider provider, long observed, boolean tracking) throws InterruptedException {
        if (closed || cleanupFailed) throw new IllegalStateException("Invalidation connection lifecycle is unavailable");
        List<RedisTrackingNode> nodes = List.copyOf(provider.trackingNodes(config.maxNodes()));
        if (nodes.isEmpty() || nodes.size() > config.maxNodes()) throw new IllegalStateException("No bounded Redis nodes");
        Epoch epoch = new Epoch(observed, tracking, nodes);
        gate.lock();
        try {
            checkGeneration(observed);
            building = epoch;
        } finally { gate.unlock(); }
        boolean complete = false;
        try {
            List<String> prefixes = tracking ? prefixes(epoch) : List.of();
            if (tracking) {
                var found = new HashSet<String>();
                Set<String> expected = null;
                for (RedisTrackingNode node : nodes) {
                    checkGeneration(observed);
                    RedisDedicatedConnection control = node.openDedicated(config.ioTimeoutMillis());
                    boolean retained = false;
                    try {
                        checkGeneration(observed);
                        boolean master = RedisTrackingTopology.master(control);
                        checkGeneration(observed);
                        if (!master) continue;
                        if (epoch.sessions.size() >= config.maxMasters()) throw new IllegalStateException("Master budget exceeded");
                        String id = provider.trackingClustered() ? RedisTrackingTopology.nodeId(control) : node.key();
                        checkGeneration(observed);
                        if (!found.add(id)) throw new IllegalStateException("Duplicate Redis master identity");
                        if (provider.trackingClustered()) {
                            var view = RedisTrackingTopology.view(control, config.maxNodes(), config.maxMasters());
                            checkGeneration(observed);
                            if (epoch.topology != null && !epoch.topology.equals(view.fingerprint()))
                                throw new IllegalStateException("Cluster topology consensus changed");
                            epoch.topology = view.fingerprint(); expected = view.masters();
                        }
                        epoch.sessions.add(new Session(epoch, node, control, id, provider.trackingClustered()));
                        retained = true;
                    } finally { if (!retained) closeOwned(control); }
                }
                if (epoch.sessions.isEmpty() || expected != null && !found.equals(expected))
                    throw new IllegalStateException("Cannot track every advertised master");
            } else {
                // Global (non-sharded) cluster PUBSUB requires one healthy node, not a slot-borrowed connection.
                RuntimeException last = null;
                for (RedisTrackingNode node : nodes) {
                    checkGeneration(observed);
                    try {
                        var control = node.openDedicated(config.ioTimeoutMillis());
                        boolean retained = false;
                        try {
                            checkGeneration(observed);
                            epoch.sessions.add(new Session(epoch, node, control, node.key(), false));
                            retained = true;
                        } finally { if (!retained) closeOwned(control); }
                        break;
                    } catch (RuntimeException unavailable) {
                        if (unavailable instanceof RedisDedicatedConnection.CleanupFailure) throw unavailable;
                        last = unavailable;
                    }
                }
                if (epoch.sessions.isEmpty()) throw new IllegalStateException("No PUBSUB node is reachable", last);
            }
            for (Session session : epoch.sessions) session.start(prefixes);
            for (Session session : epoch.sessions)
                if (!session.firstRound.await(config.healthTimeoutMillis(), TimeUnit.MILLISECONDS)
                        || session.deadline(System.nanoTime()) == 0 || epoch.failed.get())
                    throw new IllegalStateException("Invalidation heartbeat handshake failed");
            checkGeneration(observed);
            complete = true;
            return epoch;
        } finally {
            // Only this builder may close C sockets that were never handed to a worker.
            if (!complete) epoch.stop();
            for (Session session : epoch.sessions) session.closeUnstartedControl();
            epoch.initializing = false;
            if (!complete) retire(epoch);
            if (!complete) {
                gate.lock();
                try { if (building == epoch) building = null; } finally { gate.unlock(); }
            }
        }
    }
    private void checkGeneration(long observed) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Invalidation startup cancelled");
        if (closed || cleanupFailed || observed != revision.get())
            throw new IllegalStateException("Registration superseded");
    }
    private boolean install(Epoch epoch) {
        gate.lock();
        try {
            if (closed || cleanupFailed || epoch.failed.get() || epoch.revision != revision.get()) return false;
            long until = epoch.deadline();
            if (until == 0 || until - System.nanoTime() <= 0) return false;
            clearAll();
            active = epoch;
            if (building == epoch) building = null;
            mode = epoch.tracking ? NearCacheInvalidationMode.SERVER_TRACKING : NearCacheInvalidationMode.PUBSUB;
            if (epoch.tracking) {
                if (everTracked) reconnects.incrementAndGet();
                everTracked = true;
            }
            if (epoch.tracking || config.mode() == NearCacheInvalidationMode.PUBSUB) lastFailure = "";
            readyUntil = until;
            return true;
        } finally { gate.unlock(); }
    }
    private void supervise(RedisTrackingConnectionProvider provider, Epoch epoch) throws InterruptedException {
        while (!closed && !epoch.failed.get() && epoch.revision == revision.get()) {
            if (!epoch.nodes.equals(provider.trackingNodes(config.maxNodes()))) {
                lastFailure = "TOPOLOGY_POOL_MAP_CHANGED"; break;
            }
            gate.lock();
            try {
                long until = epoch.deadline();
                if (active != epoch || epoch.failed.get() || until == 0 || until - System.nanoTime() <= 0) break;
                readyUntil = until;
            } finally { gate.unlock(); }
            pause(config.heartbeatMillis());
        }
    }
    private void retire(Epoch epoch) {
        down(epoch);
        epoch.stop();
        long until = closed ? closeUntil : System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        epoch.awaitStopped(until);
        gate.lock();
        try {
            retired = epoch.quiescent() ? null : epoch;
            if (building == epoch) building = null;
        } finally { gate.unlock(); }
        // Never overlap another generation with a slow/custom owner or incomplete destruction.
    }
    private static void joinUntil(Thread thread, long until) {
        if (thread == null || thread == Thread.currentThread()) return;
        boolean interrupted = Thread.interrupted();
        try {
            while (thread.isAlive()) {
                long remaining = until - System.nanoTime();
                if (remaining <= 0) return;
                try { thread.join(Duration.ofNanos(remaining)); }
                catch (InterruptedException stopped) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
    static boolean canRenewHealth(long previous, long started, long now, long budget) {
        long elapsed = now - started;
        return elapsed >= 0 && elapsed < budget && (previous == 0 || previous - now > 0);
    }
    private static long earlierDeadline(long first, long second, long now) {
        if (first == 0 || second == 0 || first - now <= 0 || second - now <= 0) return 0;
        return now + Math.min(first - now, second - now);
    }
    void acceptTrackingMessage(String message) { acceptTrackingPayload(message); }
    void acceptTrackingPayload(Object payload) {
        var snapshot = stores.get();
        acceptTrackingPayload(payload, snapshot, keys.entityKeyPrefixes(snapshot.keySet()));
    }
    private void acceptTrackingPayload(Object payload, Map<String, RedisNearCacheCoordinator.Store> snapshot,
            Map<String, String> prefixes) {
        if (payload instanceof List<?> batch) {
            if (batch.size() > 64000) throw new IllegalStateException("Invalidation batch exceeds 64000 keys");
            for (Object key : batch) acceptTrackingKey(key, snapshot, prefixes);
        } else acceptTrackingKey(payload, snapshot, prefixes);
    }
    private void acceptTrackingKey(Object key, Map<String, RedisNearCacheCoordinator.Store> snapshot,
            Map<String, String> prefixes) {
        if (key instanceof byte[] bytes && bytes.length > 32768) return;
        acceptTrackingMessage(key == null ? null : RedisTrackingTopology.text(key), snapshot, prefixes);
    }
    private void acceptTrackingMessage(String message, Map<String, RedisNearCacheCoordinator.Store> snapshot,
            Map<String, String> prefixes) {
        if (message == null) { clearAll(); return; }
        if (message.length() > 8192) return;
        // Namespaces and typed IDs may contain ':': every matching store owns this physical key.
        for (var candidate : prefixes.entrySet()) {
            String prefix = candidate.getValue();
            if (!message.startsWith(prefix) || message.length() - prefix.length() > 4096) continue;
            var store = snapshot.get(candidate.getKey());
            if (store != null) {
                invalidations.incrementAndGet();
                store.invalidate(message.substring(prefix.length()));
            }
        }
    }
    void acceptLegacyMessage(String message) { acceptLegacyMessage(message, stores.get()); }
    private void acceptLegacyMessage(String message, Map<String, RedisNearCacheCoordinator.Store> snapshot) {
        if (message == null || message.length() > 4096) { clearAll(); return; }
        int split = message.indexOf(':');
        if (split < 0) { clearAll(); return; }
        try {
            String namespace = new String(Base64.getUrlDecoder().decode(message.substring(0, split)), StandardCharsets.UTF_8);
            String id = new String(Base64.getUrlDecoder().decode(message.substring(split + 1)), StandardCharsets.UTF_8);
            if (id.length() > 4096) { clearAll(); return; }
            var store = snapshot.get(namespace);
            if (store != null) { invalidations.incrementAndGet(); store.invalidate(id); }
        } catch (IllegalArgumentException malformed) { clearAll(); }
    }
    @Override public void close() {
        Thread thread;
        List<Epoch> epochs;
        long until;
        gate.lock();
        try {
            if (!closed) {
                closeUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                closed = true;
            }
            until = closeUntil;
            thread = runner;
            var snapshot = new LinkedHashSet<Epoch>();
            if (active != null) snapshot.add(active);
            if (building != null) snapshot.add(building);
            if (retired != null) snapshot.add(retired);
            epochs = List.copyOf(snapshot);
        } finally { gate.unlock(); }
        // Stop never invokes a Jedis method. The current I/O owners perform all destruction.
        for (Epoch epoch : epochs) epoch.stop();
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
        down(null);
        joinUntil(thread, until);
        for (Epoch epoch : epochs) epoch.awaitStopped(until);
        if (thread != null && thread.isAlive() || epochs.stream().anyMatch(epoch -> !epoch.quiescent())) {
            cleanupFailed = true;
            lastFailure = "DEDICATED_CONNECTION_STOP_TIMEOUT";
            throw new IllegalStateException("Invalidation owners did not stop within the shared two-second close budget");
        }
        if (cleanupFailed) throw new IllegalStateException("Invalidation connection destruction was not confirmed");
    }
    private final class Epoch {
        final long revision;
        final boolean tracking;
        final List<RedisTrackingNode> nodes;
        final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
        final AtomicBoolean failed = new AtomicBoolean();
        volatile boolean initializing = true;
        final Map<String, RedisNearCacheCoordinator.Store> storeSnapshot = stores.get();
        Map<String, String> prefixSnapshot = Map.of();
        String topology;
        Epoch(long revision, boolean tracking, List<RedisTrackingNode> nodes) {
            this.revision = revision; this.tracking = tracking; this.nodes = nodes;
        }
        long deadline() {
            long now = System.nanoTime(), minimum = Long.MAX_VALUE;
            for (Session session : sessions) {
                long until = session.deadline(now);
                if (until == 0) return 0;
                minimum = Math.min(minimum, until - now);
            }
            return sessions.isEmpty() ? 0 : now + minimum;
        }
        void stop() { failed.set(true); for (Session session : sessions) session.stop(); }
        void awaitStopped(long until) {
            for (Session session : sessions) {
                joinUntil(session.controller, until);
                joinUntil(session.reader, until);
            }
        }
        boolean quiescent() {
            if (initializing) return false;
            for (Session session : sessions)
                if (session.reader != null && session.reader.isAlive()
                        || session.controller != null && session.controller.isAlive()) return false;
            return true;
        }
    }
    private final class Session {
        final Epoch epoch;
        final RedisTrackingNode node;
        final RedisDedicatedConnection control;
        final String serverId;
        final boolean clustered;
        final CountDownLatch subscriptionAck = new CountDownLatch(1), firstRound = new CountDownLatch(2);
        final AtomicBoolean stopped = new AtomicBoolean();
        final ReentrantLock handoff = new ReentrantLock();
        boolean controlHandedOff, subscriberHandedOff;
        volatile RedisDedicatedConnection subscriber;
        volatile Thread reader, controller;
        volatile boolean subscribed;
        volatile long subscriptionUntil, controlUntil;
        long subscriptionId, controlId;
        Session(Epoch epoch, RedisTrackingNode node, RedisDedicatedConnection control, String serverId, boolean clustered) {
            this.epoch = epoch; this.node = node; this.control = control; this.serverId = serverId; this.clustered = clustered;
        }
        void start(List<String> prefixes) {
            handoff.lock();
            try {
                requireLive();
                Thread worker = Thread.ofVirtual().inheritInheritableThreadLocals(false)
                        .name("cachedb-tracking-control-" + Integer.toHexString(node.key().hashCode()))
                        .unstarted(() -> initializeAndHeartbeat(prefixes));
                controller = worker;
                controlHandedOff = true;
                try { worker.start(); }
                catch (RuntimeException | Error failure) {
                    controller = null; controlHandedOff = false;
                    throw failure;
                }
            } finally { handoff.unlock(); }
        }
        void closeUnstartedControl() {
            if (!controlHandedOff) closeOwned(control);
        }
        private void requireLive() {
            if (closed || stopped.get() || epoch.failed.get() || epoch.revision != revision.get()
                    || Thread.currentThread().isInterrupted())
                throw new CancellationException("Invalidation session is closed or superseded");
        }
        private void initializeAndHeartbeat(List<String> prefixes) {
            try {
                requireLive();
                controlId = RedisTrackingTopology.id(control);
                requireLive();
                subscriber = node.openDedicated(config.ioTimeoutMillis());
                requireLive();
                subscriptionId = RedisTrackingTopology.id(subscriber);
                requireLive();
                if (controlId == subscriptionId) throw new IllegalStateException("S and C must be independent sockets");
                handoff.lock();
                try {
                    requireLive();
                    Thread worker = Thread.ofVirtual().inheritInheritableThreadLocals(false)
                            .name("cachedb-tracking-" + Integer.toHexString(node.key().hashCode()))
                            .unstarted(this::subscriptionWorker);
                    reader = worker;
                    subscriberHandedOff = true;
                    try { worker.start(); }
                    catch (RuntimeException | Error failure) {
                        reader = null; subscriberHandedOff = false;
                        throw failure;
                    }
                } finally { handoff.unlock(); }
                if (!subscriptionAck.await(config.healthTimeoutMillis(), TimeUnit.MILLISECONDS) || !subscribed)
                    throw new IllegalStateException("Subscription acknowledgement timed out");
                requireLive();
                if (epoch.tracking) {
                    var arguments = new ArrayList<String>(6 + prefixes.size() * 2);
                    Collections.addAll(arguments, "TRACKING", "ON", "REDIRECT", Long.toString(subscriptionId), "BCAST");
                    for (String prefix : prefixes) { arguments.add("PREFIX"); arguments.add(prefix); }
                    requireLive();
                    if (!"OK".equals(RedisTrackingTopology.text(control.command(Command.CLIENT, arguments.toArray(String[]::new)))))
                        throw new IllegalStateException("CLIENT TRACKING was not enabled");
                    requireLive();
                }
                heartbeat();
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException failure) {
                rememberFailure(failure);
            } finally {
                stop();
                controlUntil = 0; firstRound.countDown();
                if (!subscriberHandedOff) {
                    subscriptionAck.countDown(); firstRound.countDown();
                    closeOwned(subscriber);
                }
                down(epoch);
                closeOwned(control);
            }
        }
        private void subscriptionWorker() {
            try {
                requireLive();
                readSubscription();
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException failure) {
                rememberFailure(failure);
            } finally {
                stop();
                subscribed = false; subscriptionUntil = 0;
                subscriptionAck.countDown(); firstRound.countDown(); down(epoch); closeOwned(subscriber);
            }
        }
        private void readSubscription() throws InterruptedException {
            Connection connection = subscriber.connection();
            String channel = epoch.tracking ? INVALIDATE : keys.nearInvalidationChannel();
            requireLive();
            connection.sendCommand(Command.SUBSCRIBE, channel);
            Object reply = connection.getOne();
            requireLive();
            if (!(reply instanceof List<?> ack) || ack.size() != 3
                    || !"subscribe".equals(RedisTrackingTopology.text(ack.get(0)))
                    || !channel.equals(RedisTrackingTopology.text(ack.get(1))) || !Long.valueOf(1).equals(ack.get(2)))
                throw new IllegalStateException("Invalid subscription acknowledgement");
            subscribed = true; subscriptionAck.countDown();
            long sequence = 0;
            boolean first = true;
            long budget = TimeUnit.MILLISECONDS.toNanos(config.healthTimeoutMillis());
            while (!closed && !stopped.get() && !epoch.failed.get()) {
                long started = System.nanoTime(), previous = subscriptionUntil;
                String token = Long.toString(++sequence);
                // S has one I/O owner, including sendCommand's possible error-read path.
                if (!connection.isConnected() || connection.isBroken())
                    throw new IllegalStateException("Subscription socket lost");
                requireLive();
                connection.sendCommand(Command.PING, token);
                subscriber.flush();
                boolean received = false;
                while (!received && !closed && !stopped.get() && !epoch.failed.get()) {
                    Object raw = connection.getUnflushedObject();
                    if (System.nanoTime() - started >= budget)
                        throw new IllegalStateException("Subscription heartbeat round exceeded its health budget");
                    if (!(raw instanceof List<?> message) || message.size() < 2 || message.size() > 3)
                        throw new IllegalStateException("Invalid RESP2 subscription reply");
                    String kind = RedisTrackingTopology.text(message.get(0));
                    if ("pong".equals(kind) && message.size() == 2
                            && token.equals(RedisTrackingTopology.text(message.get(1)))) {
                        received = true;
                    } else if ("message".equals(kind) && message.size() == 3
                            && channel.equals(RedisTrackingTopology.text(message.get(1)))) {
                        acceptPayload(message.get(2));
                    } else throw new IllegalStateException("Unexpected subscription reply");
                }
                long now = System.nanoTime();
                if (!received || closed || stopped.get() || epoch.failed.get()) break;
                if (!canRenewHealth(previous, started, now, budget))
                    throw new IllegalStateException("Subscription health lease expired");
                subscriptionUntil = now + budget;
                if (first) { first = false; firstRound.countDown(); }
                // Invalidations arriving after PONG wait at most this interval before the next read round.
                pause(config.heartbeatMillis());
            }
        }
        long deadline(long now) { return earlierDeadline(subscriptionUntil, controlUntil, now); }
        private void acceptPayload(Object payload) {
            if (epoch.tracking) {
                acceptTrackingPayload(payload, epoch.storeSnapshot, epoch.prefixSnapshot);
            } else {
                if (payload instanceof byte[] bytes && bytes.length > 16384) { clearAll(); return; }
                acceptLegacyMessage(payload == null ? null : RedisTrackingTopology.text(payload), epoch.storeSnapshot);
            }
        }
        private void heartbeat() throws InterruptedException {
            long topologyAt = 0;
            boolean first = true;
            long budget = TimeUnit.MILLISECONDS.toNanos(config.healthTimeoutMillis());
            while (!closed && !stopped.get() && !epoch.failed.get()) {
                requireLive();
                long started = System.nanoTime(), previous = controlUntil;
                Connection connection = control.connection();
                if (!connection.isConnected() || connection.isBroken()) throw new IllegalStateException("Control socket lost");
                // One flush/RTT for the control heartbeat, identity and periodic topology checks.
                boolean topologyDue = epoch.tracking && System.nanoTime() - topologyAt >= TimeUnit.SECONDS.toNanos(1);
                requireLive();
                connection.sendCommand(Command.PING);
                connection.sendCommand(Command.CLIENT, "ID");
                if (topologyDue) {
                    connection.sendCommand(Command.ROLE);
                    if (clustered) connection.sendCommand(Command.CLUSTER, "NODES");
                }
                if (!"PONG".equals(RedisTrackingTopology.text(connection.getOne()))
                        || !Long.valueOf(controlId).equals(connection.getOne()))
                    throw new IllegalStateException("Control connection was replaced");
                if (topologyDue) {
                    Object roleValue = connection.getOne();
                    if (!(roleValue instanceof List<?> role) || role.isEmpty()
                            || !"master".equals(RedisTrackingTopology.text(role.get(0))))
                        throw new IllegalStateException("Tracking node is no longer master");
                    if (clustered) {
                        var view = RedisTrackingTopology.parse(RedisTrackingTopology.text(connection.getOne()),
                                config.maxNodes(), config.maxMasters());
                        if (!epoch.topology.equals(view.fingerprint())) throw new IllegalStateException("Cluster topology changed");
                    }
                    topologyAt = System.nanoTime();
                }
                long now = System.nanoTime();
                if (closed || stopped.get() || epoch.failed.get()) break;
                if (!canRenewHealth(previous, started, now, budget))
                    throw new IllegalStateException("Control health lease expired");
                controlUntil = now + budget;
                if (first) { first = false; firstRound.countDown(); }
                pause(config.heartbeatMillis());
            }
        }
        void stop() {
            Thread r, c;
            handoff.lock();
            try {
                stopped.set(true);
                r = reader; c = controller;
            } finally { handoff.unlock(); }
            // Interrupt after leaving the gate: it may cancel the owner's underlying socket I/O.
            if (r != null && r != Thread.currentThread()) r.interrupt();
            if (c != null && c != Thread.currentThread()) c.interrupt();
        }
    }
}

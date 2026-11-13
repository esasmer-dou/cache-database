package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.cachedb.core.cache.NearCachePolicy;
import com.reactor.cachedb.core.config.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.*;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.JedisConnectionException;

/** Scripted registration barriers; no Redis, sleeps as race coordination, reflection or private hooks. */
@Timeout(10)
class RedisTrackingStartupCancellationTest {
    enum Phase { CREATE_C, ROLE_C, ID_C, CREATE_S, ID_S, SUBSCRIBE_S, TRACKING_C, NONE }
    enum Completion { THROW_ON_INTERRUPT, LATE_REPLY }

    @Test void closeDuringControlClientIdDoesNotCloseConcurrentlyOrOpenSubscriber() throws Exception {
        cancelled(Phase.ID_C, Completion.THROW_ON_INTERRUPT, 1, 0, 0);
    }
    @Test void lateControlClientIdReplyCannotOpenSubscriberAfterCancellation() throws Exception {
        cancelled(Phase.ID_C, Completion.LATE_REPLY, 1, 0, 0);
    }
    @Test void closeDuringSubscriberClientIdPreventsReaderHandoff() throws Exception {
        cancelled(Phase.ID_S, Completion.LATE_REPLY, 2, 0, 0);
    }
    @Test void closeDuringSubscribeAcknowledgementPreventsTrackingRegistration() throws Exception {
        cancelled(Phase.SUBSCRIBE_S, Completion.LATE_REPLY, 2, 1, 0);
    }
    @Test void closeDuringTrackingAcknowledgementNeverStartsControlHeartbeat() throws Exception {
        cancelled(Phase.TRACKING_C, Completion.LATE_REPLY, 2, 1, 1);
    }
    @Test void closeDuringControlFactoryCreationDestroysLateResultOnBuilder() throws Exception {
        cancelled(Phase.CREATE_C, Completion.LATE_REPLY, 1, 0, 0);
    }
    @Test void closeDuringSubscriberFactoryCreationDestroysLateResultOnControlOwner() throws Exception {
        cancelled(Phase.CREATE_S, Completion.LATE_REPLY, 2, 0, 0);
    }
    @Test void closeDuringRoleProbePreventsControlHandoff() throws Exception {
        cancelled(Phase.ROLE_C, Completion.LATE_REPLY, 1, 0, 0);
    }
    @Test void successfulRegistrationHandsOffBothSocketsAndCloseJoinsTheirOwners() throws Exception {
        try (var fixture = new Fixture(Phase.NONE, Completion.THROW_ON_INTERRUPT)) {
            fixture.start();
            await(fixture.coordinator::trackingReady, 2000);
            assertEquals(2, fixture.factory.created.get());
            fixture.coordinator.close();
            fixture.assertStopped();
            assertEquals(0, fixture.operations.commandCalls.get());
            assertTrue(fixture.factory.connections.stream().allMatch(connection -> connection.closer != Thread.currentThread()));
        }
    }
    @Test void closeBeforeSupervisorRunForeclosesAllFactoryCalls() throws Exception {
        try (var fixture = new Fixture(Phase.NONE, Completion.THROW_ON_INTERRUPT)) {
            fixture.coordinator.close();
            fixture.start();
            assertTrue(fixture.supervisor.join(Duration.ofSeconds(1)));
            assertEquals(0, fixture.factory.created.get());
            fixture.assertStopped();
        }
    }
    @Test void interruptedCloseCallerStillJoinsAndRetainsItsInterruptStatus() throws Exception {
        try (var fixture = new Fixture(Phase.TRACKING_C, Completion.THROW_ON_INTERRUPT)) {
            fixture.start();
            assertTrue(fixture.factory.barrier.entered.await(2, TimeUnit.SECONDS));
            try {
                Thread.currentThread().interrupt();
                fixture.coordinator.close();
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            fixture.assertStopped();
        }
    }
    @Test void cleanupFailureCannotBeReportedAsSuccessfulClose() throws Exception {
        var fixture = new Fixture(Phase.NONE, Completion.THROW_ON_INTERRUPT);
        try {
            fixture.start();
            await(fixture.coordinator::trackingReady, 2000);
            fixture.factory.failDestruction.set(true);
            assertThrows(IllegalStateException.class, fixture.coordinator::close);
            assertEquals("DEDICATED_CONNECTION_CLEANUP_FAILED", fixture.coordinator.lastFailure());
            fixture.assertStopped();
        } finally {
            fixture.factory.barrier.release.countDown();
            try { fixture.coordinator.close(); } catch (IllegalStateException expected) { /* Sticky cleanup failure. */ }
            assertEquals(0, fixture.operations.commandCalls.get());
        }
    }
    @Test void destructionFailureBeforeFactoryResultPublicationForeclosesReconnects() throws Exception {
        var fixture = new Fixture(Phase.NONE, Completion.THROW_ON_INTERRUPT);
        try {
            fixture.factory.rejectProtocol.set(true);
            fixture.factory.failDestruction.set(true);
            fixture.start();
            await(() -> "DEDICATED_CONNECTION_CLEANUP_FAILED".equals(fixture.coordinator.lastFailure()), 2000);
            assertThrows(IllegalStateException.class, fixture.coordinator::close);
            fixture.assertStopped();
            assertEquals(1, fixture.factory.created.get(), "Uncertain startup cleanup retried another socket");
        } finally {
            fixture.factory.barrier.release.countDown();
            try { fixture.coordinator.close(); } catch (IllegalStateException expected) { /* Sticky cleanup failure. */ }
            assertEquals(0, fixture.operations.commandCalls.get());
        }
    }
    @Test void alreadyCancelledDedicatedOpenNeverCallsFactory() {
        var factory = new ScriptedFactory(Phase.NONE, Completion.THROW_ON_INTERRUPT);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> RedisDedicatedConnection.open(factory, 500));
            assertEquals(0, factory.created.get());
        } finally { Thread.interrupted(); }
    }
    private void cancelled(Phase phase, Completion completion, int created, int subscribes, int registrations) throws Exception {
        try (var fixture = new Fixture(phase, completion)) {
            fixture.start();
            assertTrue(fixture.factory.barrier.entered.await(2, TimeUnit.SECONDS), "Startup did not reach " + phase);
            var error = new AtomicReference<Throwable>();
            long started = System.nanoTime();
            Thread closer = Thread.ofVirtual().inheritInheritableThreadLocals(false).start(() -> {
                try { fixture.coordinator.close(); } catch (Throwable failure) { error.set(failure); }
            });
            try {
                assertTrue(fixture.factory.barrier.interrupted.await(1, TimeUnit.SECONDS), "The current owner was not cancelled");
                assertFalse(fixture.coordinator.ready());
                assertFalse(fixture.coordinator.trackingReady());
            } finally { fixture.factory.barrier.release.countDown(); }
            assertTrue(closer.join(Duration.ofSeconds(2)), "Close did not honor its shared deadline");
            assertNull(error.get(), () -> String.valueOf(error.get()));
            assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(2500));
            fixture.assertStopped();
            assertEquals(created, fixture.factory.created.get(), "Cancelled initialization opened another socket");
            assertEquals(subscribes, fixture.factory.subscribes.get(), "Reader spawned after cancellation");
            assertEquals(registrations, fixture.factory.registrations.get(), "Tracking enabled after cancellation");
            assertEquals(0, fixture.factory.controlPings.get(), "Control heartbeat ran after cancelled registration");
            // Release all barriers again after close; no blocked helper or deferred continuation can resume.
            fixture.factory.barrier.release.countDown();
            assertEquals(created, fixture.factory.created.get());
            assertEquals(0, fixture.operations.commandCalls.get());
        }
    }
    private static void await(BooleanSupplier condition, long millis) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && until - System.nanoTime() > 0) Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
    private static final class Fixture implements AutoCloseable {
        final ScriptedFactory factory;
        final RedisTrackingTestOperations operations;
        final RedisTrackingCoordinator coordinator;
        final AtomicReference<Throwable> error = new AtomicReference<>();
        Thread supervisor;
        Fixture(Phase phase, Completion completion) {
            factory = new ScriptedFactory(phase, completion);
            operations = new RedisTrackingTestOperations(factory);
            assertEquals(0, factory.created.get(), "Fixture construction must not open a dedicated socket");
            var store = new RedisNearCacheCoordinator.Store(new NearCachePolicy(16, 10, 1048576));
            var settings = NearCacheInvalidationConfig.defaults().withMode(NearCacheInvalidationMode.SERVER_TRACKING);
            coordinator = new RedisTrackingCoordinator(operations, new RedisKeyStrategy("startup"),
                    settings, () -> Map.of("rows", store), store::clear);
        }
        void start() {
            supervisor = Thread.ofVirtual().inheritInheritableThreadLocals(false).name("startup-supervisor")
                    .unstarted(() -> {
                        try { coordinator.run(); } catch (Throwable failure) { error.set(failure); }
                    });
            supervisor.start();
        }
        void assertStopped() throws InterruptedException {
            if (supervisor != null) assertTrue(supervisor.join(Duration.ofMillis(100)), "Supervisor survived successful close");
            assertNull(error.get(), () -> String.valueOf(error.get()));
            assertFalse(coordinator.ready());
            assertEquals(factory.created.get(), factory.destroyed.get(), "Factory-owned socket retained after close");
            for (var connection : factory.connections) {
                assertTrue(connection.closed.get());
                assertEquals(0, connection.overlaps.get(), "Concurrent same-socket command/read/flush/close");
                assertFalse(connection.ioOwners.stream().anyMatch(Thread::isAlive), "I/O owner survived close");
                assertNotNull(connection.closer);
                assertSame(connection.lastOwner, connection.closer, "Non-owner performed a close/flush");
            }
        }
        @Override public void close() {
            factory.barrier.release.countDown();
            try { coordinator.close(); }
            finally { assertEquals(0, operations.commandCalls.get()); }
        }
    }
    private static final class Barrier {
        final Phase target;
        final Completion completion;
        final CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean used = new AtomicBoolean();
        Barrier(Phase target, Completion completion) { this.target = target; this.completion = completion; }
        void at(Phase phase) {
            if (target == Phase.NONE || phase != target || !used.compareAndSet(false, true)) return;
            entered.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            boolean cancelled = false;
            while (release.getCount() != 0) {
                long remaining = until - System.nanoTime();
                if (remaining <= 0) throw new AssertionError("Test barrier was not released");
                try { release.await(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException failure) {
                    interrupted.countDown();
                    if (completion == Completion.THROW_ON_INTERRUPT) {
                        Thread.currentThread().interrupt();
                        throw new JedisConnectionException("Scripted registration interrupted", failure);
                    }
                    cancelled = true; // Deliberately return a successful late reply with interrupt cleared.
                }
            }
            if (cancelled) Thread.interrupted();
        }
    }
    private static final class ScriptedFactory extends BasePooledObjectFactory<Connection> {
        final Barrier barrier;
        final AtomicBoolean failDestruction = new AtomicBoolean(), rejectProtocol = new AtomicBoolean();
        final AtomicInteger created = new AtomicInteger(), destroyed = new AtomicInteger();
        final AtomicInteger subscribes = new AtomicInteger(), registrations = new AtomicInteger(), controlPings = new AtomicInteger();
        final List<ScriptedConnection> connections = new CopyOnWriteArrayList<>();
        ScriptedFactory(Phase phase, Completion completion) { barrier = new Barrier(phase, completion); }
        @Override public Connection create() {
            assertTrue(Thread.currentThread().isVirtual(), "Factory I/O must run on its virtual-thread owner");
            int index = created.incrementAndGet();
            if (index > 2) throw new AssertionError("Cancelled generation retried or exceeded its two-socket budget");
            var connection = new ScriptedConnection(this, index);
            connections.add(connection);
            barrier.at(index == 1 ? Phase.CREATE_C : Phase.CREATE_S);
            return connection;
        }
        @Override public PooledObject<Connection> wrap(Connection connection) { return new DefaultPooledObject<>(connection); }
        @Override public void destroyObject(PooledObject<Connection> owned) {
            var connection = (ScriptedConnection) owned.getObject();
            assertTrue(connection.closed.get(), "Factory destruction preceded owner close");
            assertSame(connection.closer, Thread.currentThread(), "Destruction ran on a different owner");
            destroyed.incrementAndGet();
            if (failDestruction.get()) throw new IllegalStateException("Scripted destruction failure");
        }
    }
    private record Reply(Phase phase, Object value) {}
    private static final class ScriptedConnection extends Connection {
        final ScriptedFactory factory;
        final int index;
        final Deque<Reply> replies = new ArrayDeque<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger inIo = new AtomicInteger(), overlaps = new AtomicInteger();
        final Set<Thread> ioOwners = ConcurrentHashMap.newKeySet();
        volatile Thread lastOwner, closer;
        ScriptedConnection(ScriptedFactory factory, int index) {
            this.factory = factory; this.index = index;
            if (factory.rejectProtocol.get()) protocol = RedisProtocol.RESP3;
        }
        private <T> T io(Supplier<T> operation) {
            if (inIo.incrementAndGet() != 1) overlaps.incrementAndGet();
            Thread current = Thread.currentThread();
            assertTrue(current.isVirtual(), "I/O ran outside a virtual-thread owner");
            ioOwners.add(current); lastOwner = current;
            try {
                assertFalse(closed.get(), "I/O ran after close");
                return operation.get();
            } finally { inIo.decrementAndGet(); }
        }
        @Override public boolean isConnected() { return !closed.get(); }
        @Override public boolean isBroken() { return closed.get(); }
        @Override public void setTimeoutInfinite() { throw new AssertionError("Infinite subscription timeout"); }
        @Override public void sendCommand(ProtocolCommand command) { sendCommand(command, new String[0]); }
        @Override public void sendCommand(ProtocolCommand command, String... arguments) {
            io(() -> {
                if (command == Command.ROLE) replies.addLast(new Reply(Phase.ROLE_C, List.of("master")));
                else if (command == Command.CLIENT && "ID".equals(arguments[0]))
                    replies.addLast(new Reply(index == 1 ? Phase.ID_C : Phase.ID_S, (long) index));
                else if (command == Command.CLIENT && "TRACKING".equals(arguments[0])) {
                    factory.registrations.incrementAndGet();
                    replies.addLast(new Reply(Phase.TRACKING_C, "OK"));
                } else if (command == Command.SUBSCRIBE) {
                    factory.subscribes.incrementAndGet();
                    replies.addLast(new Reply(Phase.SUBSCRIBE_S, List.of("subscribe", arguments[0], 1L)));
                } else if (command == Command.PING) {
                    if (index == 1) factory.controlPings.incrementAndGet();
                    replies.addLast(new Reply(Phase.NONE, arguments.length == 0 ? "PONG" : List.of("pong", arguments[0])));
                } else throw new AssertionError("Unexpected scripted command " + command);
                return null;
            });
        }
        private Object reply() {
            Reply next = replies.removeFirst();
            factory.barrier.at(next.phase());
            return next.value();
        }
        @Override public Object getOne() { return io(this::reply); }
        @Override public Object getUnflushedObject() { return io(this::reply); }
        @Override public List<Object> getMany(int count) {
            return io(() -> {
                assertEquals(0, count, "Only an S flush is expected");
                return List.of();
            });
        }
        @Override public void close() {
            Thread current = Thread.currentThread();
            assertTrue(current.isVirtual(), "Non-owner close may flush Jedis output");
            if (inIo.incrementAndGet() != 1) overlaps.incrementAndGet();
            try {
                assertTrue(current == lastOwner || lastOwner == null, "Close raced an ownership handoff");
                closer = current;
                if (lastOwner == null) lastOwner = current;
                ioOwners.add(current);
                assertTrue(closed.compareAndSet(false, true), "Dedicated connection closed twice");
            } finally { inIo.decrementAndGet(); }
        }
    }
}

package com.reactor.cachedb.examples.demo;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class DemoScenarioActionQueue implements AutoCloseable {

    private final ExecutorService actionExecutor;
    private final ExecutorService stopExecutor;
    private final AtomicReference<String> actionState = new AtomicReference<>("IDLE");
    private final AtomicReference<String> actionLabel = new AtomicReference<>("Hazir");
    private final AtomicReference<String> actionError = new AtomicReference<>("");
    private final AtomicLong actionSequence = new AtomicLong();
    private final AtomicLong lastActionQueuedAtEpochMillis = new AtomicLong();
    private final AtomicLong lastActionCompletedAtEpochMillis = new AtomicLong();
    private final AtomicLong lastActionId = new AtomicLong();

    public DemoScenarioActionQueue() {
        this.actionExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("cachedb-demo-action-"));
        this.stopExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("cachedb-demo-stop-"));
    }

    public void enqueueAction(String label, Runnable action) {
        long actionId = actionSequence.incrementAndGet();
        lastActionId.set(actionId);
        actionState.set("QUEUED");
        actionLabel.set(defaultString(label, "Islem kuyruklandi"));
        actionError.set("");
        lastActionQueuedAtEpochMillis.set(System.currentTimeMillis());
        actionExecutor.submit(() -> runAction(actionId, label, action));
    }

    public void enqueuePriorityStop(String label, Runnable action) {
        enqueuePriorityAction(label, action);
    }

    public void enqueuePriorityAction(String label, Runnable action) {
        long actionId = actionSequence.incrementAndGet();
        lastActionId.set(actionId);
        actionState.set("QUEUED");
        actionLabel.set(defaultString(label, "Yuk durduruluyor"));
        actionError.set("");
        lastActionQueuedAtEpochMillis.set(System.currentTimeMillis());
        stopExecutor.submit(() -> runAction(actionId, label, action));
    }

    public DemoScenarioActionStateSnapshot snapshot() {
        return new DemoScenarioActionStateSnapshot(
                actionState.get(),
                actionLabel.get(),
                actionError.get(),
                lastActionQueuedAtEpochMillis.get(),
                lastActionCompletedAtEpochMillis.get(),
                lastActionId.get()
        );
    }

    private void runAction(long actionId, String label, Runnable action) {
        if (lastActionId.get() != actionId) {
            return;
        }
        actionState.set("RUNNING");
        actionLabel.set(defaultString(label, "Islem calisiyor"));
        try {
            action.run();
            if (lastActionId.get() == actionId) {
                actionState.set("IDLE");
                actionLabel.set(defaultString(label, "Islem") + " tamamlandi");
                actionError.set("");
            }
        } catch (RuntimeException exception) {
            if (lastActionId.get() == actionId) {
                actionState.set("ERROR");
                actionError.set(exception.getClass().getSimpleName() + ": " + defaultString(exception.getMessage(), ""));
            }
        } finally {
            lastActionCompletedAtEpochMillis.set(System.currentTimeMillis());
        }
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public void close() {
        actionExecutor.shutdownNow();
        stopExecutor.shutdownNow();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicLong sequence = new AtomicLong();

        private NamedThreadFactory(String prefix) {
            this.prefix = Objects.requireNonNull(prefix, "prefix");
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}

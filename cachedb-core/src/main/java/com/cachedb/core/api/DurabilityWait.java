package com.reactor.cachedb.core.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Monotonic, bounded polling. Does not retry or resubmit writes. */
public final class DurabilityWait {
    private DurabilityWait() {}

    public static boolean await(BooleanSupplier durable, Duration timeout) {
        return await(durable, timeout, MonotonicTicker.INSTANCE, BlockingSleeper.INSTANCE);
    }

    private static final class MonotonicTicker implements LongSupplier {
        static final MonotonicTicker INSTANCE = new MonotonicTicker();

        public long getAsLong() {
            return System.nanoTime();
        }
    }

    private static final class BlockingSleeper implements Sleeper {
        static final BlockingSleeper INSTANCE = new BlockingSleeper();

        public void sleep(long nanos) throws InterruptedException {
            TimeUnit.NANOSECONDS.sleep(nanos);
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    // Package-private clock/wait seam keeps timeout tests deterministic without real sleeps.
    static boolean await(
            BooleanSupplier durable, Duration timeout, LongSupplier ticker, Sleeper sleeper) {
        Objects.requireNonNull(durable, "durable");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("timeout must be greater than zero");
        long budget = timeout.toNanos(), start = ticker.getAsLong();
        int attempt = 0;
        while (!Thread.currentThread().isInterrupted()) {
            if (durable.getAsBoolean()) return true;
            long remaining = budget - (ticker.getAsLong() - start);
            if (remaining <= 0) return false;
            long sleep = TimeUnit.MILLISECONDS.toNanos(attempt == 0 ? 5 : attempt == 1 ? 10 : 25);
            attempt = Math.min(attempt + 1, 2);
            try {
                sleeper.sleep(Math.min(sleep, remaining));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}

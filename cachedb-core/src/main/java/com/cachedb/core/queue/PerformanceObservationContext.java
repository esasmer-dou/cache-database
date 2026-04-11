package com.reactor.cachedb.core.queue;

import java.util.Objects;
import java.util.function.Supplier;

public final class PerformanceObservationContext {

    private static final ThreadLocal<String> CURRENT_TAG = new ThreadLocal<>();

    private PerformanceObservationContext() {
    }

    public static String currentTag() {
        String tag = CURRENT_TAG.get();
        return tag == null || tag.isBlank() ? "" : tag;
    }

    public static void runWithTag(String tag, Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        String previous = CURRENT_TAG.get();
        CURRENT_TAG.set(normalize(tag));
        try {
            runnable.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T supplyWithTag(String tag, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        String previous = CURRENT_TAG.get();
        CURRENT_TAG.set(normalize(tag));
        try {
            return supplier.get();
        } finally {
            restore(previous);
        }
    }

    private static String normalize(String tag) {
        return tag == null ? "" : tag.trim();
    }

    private static void restore(String previous) {
        if (previous == null || previous.isBlank()) {
            CURRENT_TAG.remove();
        } else {
            CURRENT_TAG.set(previous);
        }
    }
}

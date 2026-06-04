package com.reactor.cachedb.core.route;

import java.util.Objects;
import java.util.function.Supplier;

public final class RouteCacheContext {

    private static final ThreadLocal<RouteCacheContract> CURRENT_CONTRACT = new ThreadLocal<>();

    private RouteCacheContext() {
    }

    public static RouteCacheContract currentContract() {
        return CURRENT_CONTRACT.get();
    }

    public static void runWithContract(RouteCacheContract contract, Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");
        RouteCacheContract previous = CURRENT_CONTRACT.get();
        if (contract == null) {
            CURRENT_CONTRACT.remove();
        } else {
            CURRENT_CONTRACT.set(contract);
        }
        try {
            runnable.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T supplyWithContract(RouteCacheContract contract, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        RouteCacheContract previous = CURRENT_CONTRACT.get();
        if (contract == null) {
            CURRENT_CONTRACT.remove();
        } else {
            CURRENT_CONTRACT.set(contract);
        }
        try {
            return supplier.get();
        } finally {
            restore(previous);
        }
    }

    private static void restore(RouteCacheContract previous) {
        if (previous == null) {
            CURRENT_CONTRACT.remove();
        } else {
            CURRENT_CONTRACT.set(previous);
        }
    }
}

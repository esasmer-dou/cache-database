package com.reactor.cachedb.annotations;

/** Compile-time byte constants for readable annotation memory budgets. */
public final class CacheMemoryBudget {
    public static final long MIB_1 = 1_048_576L;
    public static final long MIB_2 = 2L * MIB_1;
    public static final long MIB_4 = 4L * MIB_1;
    public static final long MIB_8 = 8L * MIB_1;
    public static final long MIB_16 = 16L * MIB_1;
    public static final long MIB_32 = 32L * MIB_1;
    public static final long MIB_64 = 64L * MIB_1;
    public static final long MIB_128 = 128L * MIB_1;
    public static final long MIB_256 = 256L * MIB_1;

    private CacheMemoryBudget() {
    }
}

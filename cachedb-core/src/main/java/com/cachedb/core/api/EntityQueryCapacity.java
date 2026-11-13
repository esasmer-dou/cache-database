package com.reactor.cachedb.core.api;

import java.util.Objects;

/**
 * I/O-free description of the current full-entity query row budget, including lookahead rows.
 * This is not authorization, index completeness, a snapshot, or a promise that a query succeeds.
 * UNRESTRICTED removes only this row-budget restriction; other query and transport bounds remain.
 * UNKNOWN is distinct from both unrestricted and FINITE with zero rows.
 */
public record EntityQueryCapacity(Kind kind, int maxRows) {
    public enum Kind { UNKNOWN, UNRESTRICTED, FINITE }

    private static final EntityQueryCapacity UNKNOWN = new EntityQueryCapacity(Kind.UNKNOWN, 0);
    private static final EntityQueryCapacity UNRESTRICTED = new EntityQueryCapacity(Kind.UNRESTRICTED, 0);
    private static final EntityQueryCapacity ZERO = new EntityQueryCapacity(Kind.FINITE, 0);

    public EntityQueryCapacity {
        Objects.requireNonNull(kind, "kind");
        if (maxRows < 0 || kind != Kind.FINITE && maxRows != 0)
            throw new IllegalArgumentException("Only FINITE capacity has a nonnegative row limit");
    }

    public static EntityQueryCapacity unknown() { return UNKNOWN; }
    public static EntityQueryCapacity unrestricted() { return UNRESTRICTED; }
    public static EntityQueryCapacity finite(int maxRows) {
        return maxRows == 0 ? ZERO : new EntityQueryCapacity(Kind.FINITE, maxRows);
    }
}

package com.reactor.cachedb.core.model;

import java.util.Objects;

/** A command descriptor, never an entity field value or a serialized SQL value. */
public record PatchDeltaSpec(String fieldName, String columnName, Number delta, long minBound, long maxBound) {
    public PatchDeltaSpec {
        Objects.requireNonNull(fieldName); Objects.requireNonNull(columnName); Objects.requireNonNull(delta);
        if (!(delta instanceof Integer || delta instanceof Long || delta instanceof Double || delta instanceof java.math.BigDecimal))
            throw new IllegalArgumentException("Delta must use an immutable supported numeric type");
        if (minBound > maxBound) throw new IllegalArgumentException("Delta min exceeds max");
        if (delta instanceof Double number && !Double.isFinite(number))
            throw new IllegalArgumentException("Delta must be finite");
    }
}

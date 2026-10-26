package com.reactor.cachedb.core.model;

import java.math.BigDecimal;
import com.reactor.cachedb.core.exception.CacheDeltaBoundsException;

/** Exact decimal arithmetic before the existing optimistic Redis acceptance boundary. */
public final class NumericDeltas {
    private NumericDeltas() {}
    public static Object apply(String entityName, Object id, String type, PatchDeltaSpec spec, Object current) {
        if (!(current instanceof Number number)) throw new IllegalArgumentException("Delta target is not a non-null number");
        BigDecimal before = NumericValues.boundedDecimal(number);
        BigDecimal amount = NumericValues.boundedDecimal(spec.delta());
        BigDecimal after = before.add(amount);
        if (after.compareTo(BigDecimal.valueOf(spec.minBound())) < 0 || after.compareTo(BigDecimal.valueOf(spec.maxBound())) > 0)
            throw new CacheDeltaBoundsException(entityName, id, spec.columnName(), before, amount, spec.minBound(), spec.maxBound());
        if ("int".equals(type) || "java.lang.Integer".equals(type)) return after.intValueExact();
        if ("long".equals(type) || "java.lang.Long".equals(type)) return after.longValueExact();
        if ("java.math.BigDecimal".equals(type)) return after;
        if ("double".equals(type) || "java.lang.Double".equals(type)) {
            double value = after.doubleValue();
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Delta result is not finite");
            return value;
        }
        throw new IllegalArgumentException("Unsupported delta field type: " + type);
    }
}

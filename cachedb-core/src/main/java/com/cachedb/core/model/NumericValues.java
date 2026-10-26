package com.reactor.cachedb.core.model;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Bounds decimal scale alignment before arithmetic can allocate oversized powers of ten. */
public final class NumericValues {
    private NumericValues() {}

    public static BigDecimal boundedDecimal(Number value) {
        if (value instanceof BigInteger integer && integer.bitLength() > 3402)
            throw new IllegalArgumentException("Numeric precision exceeds 1024 digits");
        BigDecimal decimal = value instanceof BigDecimal exact ? exact : new BigDecimal(value.toString());
        if (decimal.precision() > 1024 || decimal.scale() < -1024 || decimal.scale() > 1024)
            throw new IllegalArgumentException("Numeric precision/scale exceeds arithmetic budget");
        return decimal;
    }
}

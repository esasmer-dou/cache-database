package com.reactor.cachedb.core.query;

import java.math.BigDecimal;
import java.util.OptionalDouble;

/** Count includes matching rows; average ignores null numeric values. Empty min/max are null. */
public record AggregateResult(long count, BigDecimal sum, BigDecimal min, BigDecimal max, OptionalDouble average) {
    public static final class Accumulator {
        private long count, numericCount;
        private BigDecimal sum = BigDecimal.ZERO, min, max;
        public void add(Object value) {
            count++;
            if (value == null) return;
            if (!(value instanceof Number numeric)) throw new IllegalArgumentException("Aggregate field is not numeric");
            BigDecimal number = com.reactor.cachedb.core.model.NumericValues.boundedDecimal(numeric);
            numericCount++; sum = sum.add(number);
            min = min == null ? number : min.min(number);
            max = max == null ? number : max.max(number);
        }
        public AggregateResult result() {
            return new AggregateResult(count, sum, min, max, numericCount == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of(sum.divide(BigDecimal.valueOf(numericCount), java.math.MathContext.DECIMAL128).doubleValue()));
        }
    }
}

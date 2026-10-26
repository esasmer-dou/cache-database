package com.reactor.cachedb.core.query;

import java.math.BigDecimal;
import java.util.OptionalDouble;

/** Count includes matching rows; average ignores null numeric values. Empty min/max are null. */
public record AggregateResult(long count, BigDecimal sum, BigDecimal min, BigDecimal max, OptionalDouble average,
        BigDecimal decimalAverage) {
    public AggregateResult(long count, BigDecimal sum, BigDecimal min, BigDecimal max, OptionalDouble average) {
        this(count, sum, min, max, average, average != null && average.isPresent() && Double.isFinite(average.getAsDouble())
                ? BigDecimal.valueOf(average.getAsDouble()) : null);
    }
    /** Keep the original five-component equality/hash contract for existing callers. */
    @Override public boolean equals(Object other) {
        return this == other || other instanceof AggregateResult value && count == value.count
                && java.util.Objects.equals(sum,value.sum) && java.util.Objects.equals(min,value.min)
                && java.util.Objects.equals(max,value.max) && java.util.Objects.equals(average,value.average);
    }
    @Override public int hashCode() {
        int hash = Long.hashCode(count);
        hash = 31 * hash + java.util.Objects.hashCode(sum); hash = 31 * hash + java.util.Objects.hashCode(min);
        hash = 31 * hash + java.util.Objects.hashCode(max); return 31 * hash + java.util.Objects.hashCode(average);
    }
    public BigDecimal numericValue(AggregateFunction function) {
        return switch (function) {
            case COUNT -> BigDecimal.valueOf(count);
            case SUM -> sum;
            case MIN -> min;
            case MAX -> max;
            case AVG -> decimalAverage;
        };
    }
    public static final class Accumulator {
        private long count, numericCount;
        private BigDecimal sum = BigDecimal.ZERO, min, max;
        public void addCount(long rows) {
            if (rows < 0) throw new IllegalArgumentException("Negative aggregate count");
            count = Math.addExact(count, rows);
        }
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
            BigDecimal mean = numericCount == 0 ? null : sum.divide(BigDecimal.valueOf(numericCount), java.math.MathContext.DECIMAL128);
            return new AggregateResult(count, sum, min, max, mean == null ? OptionalDouble.empty() : OptionalDouble.of(mean.doubleValue()), mean);
        }
    }
}

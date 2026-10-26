package com.reactor.cachedb.core.exception;

import java.math.BigDecimal;

public final class CacheDeltaBoundsException extends CacheDatabaseException {
    private final String entityName, columnName;
    private final Object entityId;
    private final BigDecimal currentValue, deltaValue;
    private final long minBound, maxBound;
    public CacheDeltaBoundsException(String entityName, Object entityId, String columnName,
            BigDecimal currentValue, BigDecimal deltaValue, long minBound, long maxBound) {
        super("Numeric delta exceeds bounds for " + entityName + '.' + columnName);
        this.entityName = entityName; this.entityId = entityId; this.columnName = columnName;
        this.currentValue = currentValue; this.deltaValue = deltaValue; this.minBound = minBound; this.maxBound = maxBound;
    }
    public String entityName() { return entityName; }
    public Object entityId() { return entityId; }
    public String columnName() { return columnName; }
    public BigDecimal currentValue() { return currentValue; }
    public BigDecimal deltaValue() { return deltaValue; }
    public long minBound() { return minBound; }
    public long maxBound() { return maxBound; }
}

package com.reactor.cachedb.core.model;

public final class OptimisticWriteConflictException extends IllegalStateException {

    private final String entityName;
    private final String id;
    private final long expectedVersion;
    private final long actualVersion;

    public OptimisticWriteConflictException(
            String entityName,
            Object id,
            long expectedVersion,
            long actualVersion
    ) {
        super("Optimistic write conflict for " + entityName + " id=" + id
                + ": expected version " + expectedVersion + " but current version is " + actualVersion);
        this.entityName = entityName;
        this.id = String.valueOf(id);
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String entityName() {
        return entityName;
    }

    public String id() {
        return id;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }
}

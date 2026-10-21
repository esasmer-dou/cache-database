package com.reactor.cachedb.core.model;

public final class OptimisticWriteConflictException extends IllegalStateException {

    private final String entityName;
    private final String id;
    private final long expectedVersion;
    private final long actualVersion;

    public OptimisticWriteConflictException(
            String entityName, Object id, long expectedVersion, long actualVersion) {
        super(
                new StringBuilder("Optimistic write conflict for ")
                        .append(entityName)
                        .append(" id=")
                        .append(id)
                        .append(": expected version ")
                        .append(expectedVersion)
                        .append(" but current version is ")
                        .append(actualVersion)
                        .toString());
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

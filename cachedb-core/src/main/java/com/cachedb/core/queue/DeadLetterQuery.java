package com.reactor.cachedb.core.queue;

public record DeadLetterQuery(
        int limit,
        String cursor,
        String entityName,
        String operationType,
        String entityId,
        String errorType
) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int limit = 100;
        private String cursor;
        private String entityName;
        private String operationType;
        private String entityId;
        private String errorType;

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder cursor(String cursor) {
            this.cursor = cursor;
            return this;
        }

        public Builder entityName(String entityName) {
            this.entityName = entityName;
            return this;
        }

        public Builder operationType(String operationType) {
            this.operationType = operationType;
            return this;
        }

        public Builder entityId(String entityId) {
            this.entityId = entityId;
            return this;
        }

        public Builder errorType(String errorType) {
            this.errorType = errorType;
            return this;
        }

        public DeadLetterQuery build() {
            return new DeadLetterQuery(limit, cursor, entityName, operationType, entityId, errorType);
        }
    }
}

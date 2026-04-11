package com.reactor.cachedb.core.queue;

public record ReconciliationQuery(
        int limit,
        String cursor,
        String status,
        String entityName,
        String operationType,
        String entityId
) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int limit = 100;
        private String cursor;
        private String status;
        private String entityName;
        private String operationType;
        private String entityId;

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder cursor(String cursor) {
            this.cursor = cursor;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
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

        public ReconciliationQuery build() {
            return new ReconciliationQuery(limit, cursor, status, entityName, operationType, entityId);
        }
    }
}

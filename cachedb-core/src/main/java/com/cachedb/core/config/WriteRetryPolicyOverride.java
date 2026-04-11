package com.reactor.cachedb.core.config;

import com.reactor.cachedb.core.model.OperationType;

public record WriteRetryPolicyOverride(
        String entityName,
        OperationType operationType,
        int maxRetries,
        long backoffMillis
) {
    public static Builder builder() {
        return new Builder();
    }

    public boolean matches(String candidateEntityName, OperationType candidateOperationType) {
        boolean entityMatches = entityName == null || entityName.isBlank() || entityName.equals(candidateEntityName);
        boolean operationMatches = operationType == null || operationType == candidateOperationType;
        return entityMatches && operationMatches;
    }

    public int specificity() {
        int score = 0;
        if (entityName != null && !entityName.isBlank()) {
            score += 2;
        }
        if (operationType != null) {
            score += 1;
        }
        return score;
    }

    public static final class Builder {
        private String entityName;
        private OperationType operationType;
        private int maxRetries = 3;
        private long backoffMillis = 1_000;

        public Builder entityName(String entityName) {
            this.entityName = entityName;
            return this;
        }

        public Builder operationType(OperationType operationType) {
            this.operationType = operationType;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder backoffMillis(long backoffMillis) {
            this.backoffMillis = backoffMillis;
            return this;
        }

        public WriteRetryPolicyOverride build() {
            return new WriteRetryPolicyOverride(entityName, operationType, maxRetries, backoffMillis);
        }
    }
}

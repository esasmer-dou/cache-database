package com.reactor.cachedb.core.config;

import com.reactor.cachedb.core.model.OperationType;
import com.reactor.cachedb.core.queue.QueuedWriteOperation;

public record EntityFlushPolicy(
        String entityName,
        OperationType operationType,
        boolean stateCompactionEnabled,
        boolean preferCopy,
        boolean preferMultiRow,
        int maxBatchSize,
        int statementRowLimit,
        int copyThreshold,
        PersistenceSemantics persistenceSemantics
) {
    public EntityFlushPolicy(
            String entityName,
            OperationType operationType,
            boolean stateCompactionEnabled,
            boolean preferCopy,
            boolean preferMultiRow,
            int maxBatchSize,
            int statementRowLimit,
            int copyThreshold
    ) {
        this(
                entityName,
                operationType,
                stateCompactionEnabled,
                preferCopy,
                preferMultiRow,
                maxBatchSize,
                statementRowLimit,
                copyThreshold,
                stateCompactionEnabled ? PersistenceSemantics.LATEST_STATE : PersistenceSemantics.EXACT_SEQUENCE
        );
    }

    public boolean matches(QueuedWriteOperation operation) {
        if (entityName == null || entityName.isBlank() || !entityName.equals(operation.entityName())) {
            return false;
        }
        return operationType == null || operationType == operation.type();
    }

    public int specificity() {
        return operationType == null ? 1 : 2;
    }

    public PersistenceSemantics effectivePersistenceSemantics() {
        return persistenceSemantics == null
                ? (stateCompactionEnabled ? PersistenceSemantics.LATEST_STATE : PersistenceSemantics.EXACT_SEQUENCE)
                : persistenceSemantics;
    }
}

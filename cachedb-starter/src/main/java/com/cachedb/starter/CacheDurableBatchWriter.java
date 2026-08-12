package com.reactor.cachedb.starter;

import com.reactor.cachedb.core.model.WriteReceipt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Bounded write-behind batch helper. It batches repository calls, caps pending
 * durability receipts, and does not return from finish until SQL is durable.
 */
public final class CacheDurableBatchWriter<T, ID> implements AutoCloseable {
    private final String operation;
    private final int batchSize;
    private final int maxPendingReceipts;
    private final Function<Collection<T>, List<WriteReceipt<T, ID>>> writer;
    private final DurabilityAwaiter durabilityAwaiter;
    private final ArrayList<T> buffer;
    private final ArrayList<WriteReceipt<?, ?>> pending;
    private long submittedRows;
    private int writeBatches;
    private int durabilityWaits;
    private CacheDurableBatchResult result;

    CacheDurableBatchWriter(
            String operation,
            int batchSize,
            int maxPendingReceipts,
            Function<Collection<T>, List<WriteReceipt<T, ID>>> writer,
            DurabilityAwaiter durabilityAwaiter
    ) {
        if (operation == null || operation.isBlank() || operation.length() > 128) {
            throw new IllegalArgumentException("operation must be non-blank and at most 128 characters");
        }
        if (batchSize <= 0 || batchSize > 10_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 10000");
        }
        if (maxPendingReceipts < batchSize || maxPendingReceipts > 100_000) {
            throw new IllegalArgumentException("maxPendingReceipts must be between batchSize and 100000");
        }
        if (writer == null || durabilityAwaiter == null) {
            throw new IllegalArgumentException("writer and durabilityAwaiter must not be null");
        }
        this.operation = operation.trim();
        this.batchSize = batchSize;
        this.maxPendingReceipts = maxPendingReceipts;
        this.writer = writer;
        this.durabilityAwaiter = durabilityAwaiter;
        this.buffer = new ArrayList<>(batchSize);
        this.pending = new ArrayList<>(maxPendingReceipts);
    }

    public void add(T entity) {
        requireOpen();
        if (entity == null) {
            throw new IllegalArgumentException("entity must not be null");
        }
        buffer.add(entity);
        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    public void addAll(Iterable<? extends T> entities) {
        if (entities == null) {
            throw new IllegalArgumentException("entities must not be null");
        }
        for (T entity : entities) {
            add(entity);
        }
    }

    public void flush() {
        requireOpen();
        if (buffer.isEmpty()) {
            return;
        }
        List<T> batch = List.copyOf(buffer);
        List<WriteReceipt<T, ID>> receipts = writer.apply(batch);
        if (receipts == null || receipts.size() != batch.size() || receipts.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException("writer must return one non-null receipt per submitted entity for " + operation);
        }
        submittedRows += batch.size();
        writeBatches++;
        pending.addAll(receipts);
        buffer.clear();
        if (pending.size() >= maxPendingReceipts) {
            awaitPending();
        }
    }

    public CacheDurableBatchResult finish() {
        if (result != null) {
            return result;
        }
        flush();
        awaitPending();
        result = new CacheDurableBatchResult(operation, submittedRows, writeBatches, durabilityWaits);
        return result;
    }

    @Override
    public void close() {
        finish();
    }

    private void awaitPending() {
        if (pending.isEmpty()) {
            return;
        }
        durabilityAwaiter.await(List.copyOf(pending), operation);
        durabilityWaits++;
        pending.clear();
    }

    private void requireOpen() {
        if (result != null) {
            throw new IllegalStateException("batch writer is already finished for " + operation);
        }
    }

    @FunctionalInterface
    interface DurabilityAwaiter {
        void await(Collection<? extends WriteReceipt<?, ?>> receipts, String operation);
    }
}

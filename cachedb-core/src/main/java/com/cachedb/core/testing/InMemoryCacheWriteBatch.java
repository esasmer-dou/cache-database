package com.reactor.cachedb.core.testing;

import com.reactor.cachedb.core.api.*;
import com.reactor.cachedb.core.codec.Utf8Length;
import com.reactor.cachedb.core.model.*;

import java.util.*;

/** Shared session lock makes both CAS validation and all mutations one acceptance unit. */
public final class InMemoryCacheWriteBatch implements CacheWriteBatch {
    private record Command(
            InMemoryEntityRepository<Object, Object> repository,
            Object id,
            String payload,
            long expected) {}

    private final InMemoryCacheDatabase database;
    private final String id;
    private final List<Command> commands = new ArrayList<>();
    private final Set<String> identities = new HashSet<>();
    private CacheWriteBatchReceipt receipt;
    private long bytes;

    InMemoryCacheWriteBatch(InMemoryCacheDatabase database, String id) {
        this.database = database;
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("batch ID must not be blank");
        this.id = id;
    }

    public <T> CacheWriteBatch put(T entity) {
        return put(entity, -1);
    }

    @SuppressWarnings("unchecked")
    public <T> CacheWriteBatch put(T entity, long expected) {
        InMemoryEntityRepository<Object, Object> repository =
                (InMemoryEntityRepository<Object, Object>)
                        (Object) database.registered(entity.getClass());
        add(
                new Command(
                        repository,
                        repository.metadata.idAccessor().apply(entity),
                        Objects.requireNonNull(
                                repository.codec.toRedisValue(entity), "Serialized entity payload"),
                        expected));
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T, ID> CacheWriteBatch delete(Class<T> type, ID id) {
        add(
                new Command(
                        (InMemoryEntityRepository<Object, Object>)
                                (Object) database.registered(type),
                        id,
                        null,
                        -1));
        return this;
    }

    private void add(Command command) {
        if (command.repository.metadata.tenantFieldName() != null)
            throw new IllegalStateException("Tenant entities require ownership-checked repository writes; atomic batch tenant ownership is not supported");
        if (receipt != null || commands.size() >= 100)
            throw new IllegalStateException("Batch committed or exceeds 100 operations");
        Objects.requireNonNull(command.id, "id");
        long nextBytes =
                bytes
                        + Utf8Length.of(command.id.toString())
                        + (command.payload == null ? 0 : Utf8Length.of(command.payload));
        if (nextBytes > 4L * 1024 * 1024)
            throw new IllegalArgumentException("Atomic batch exceeds 4 MiB");
        if (!identities.add(
                new StringBuilder()
                        .append(command.repository.metadata.redisNamespace())
                        .append('\n')
                        .append(command.id)
                        .toString()))
            throw new IllegalArgumentException("Duplicate identity in batch");
        commands.add(command);
        bytes = nextBytes;
    }

    public CacheWriteBatchReceipt commit() {
        database.lock.lock();
        try {
            if (receipt != null) return receipt;
            if (commands.isEmpty()) throw new IllegalStateException("Empty batch");
            List<String> signature = new ArrayList<>(commands.size() * 4);
            for (Command command : commands) {
                signature.add(command.repository.metadata.redisNamespace());
                signature.add(command.id.toString());
                signature.add(command.payload);
                signature.add(Long.toString(command.expected));
            }
            var prior = database.batches.get(id);
            if (prior != null) {
                if (!prior.signature().equals(signature))
                    throw new IllegalArgumentException("Batch id reused with different operations");
                return receipt = prior.receipt();
            }
            for (Command command : commands)
                command.repository.checkVersion(command.id, command.expected);
            Map<InMemoryEntityRepository<Object, Object>, Object> checkpoints =
                    new LinkedHashMap<>();
            for (Command command : commands)
                if (!checkpoints.containsKey(command.repository))
                    checkpoints.put(command.repository, command.repository.checkpoint());
            try {
                List<Long> versions = new ArrayList<>(commands.size());
                List<WriteDependency> dependencies = new ArrayList<>(commands.size());
                for (Command command : commands) {
                    WriteReceipt<Object, Object> accepted =
                            command.payload == null
                                    ? command.repository.deleteWithReceipt(command.id)
                                    : command.repository.save(
                                            command.repository.codec.fromRedisValue(
                                                    command.payload),
                                            command.expected);
                    versions.add(accepted.version());
                    dependencies.add(accepted.asDependency());
                }
                receipt =
                        new CacheWriteBatchReceipt(
                                id, "in-memory:".concat(id), versions, dependencies);
                database.batches.put(
                        id,
                        new InMemoryCacheDatabase.BatchState(
                                Collections.unmodifiableList(signature), receipt));
                return receipt;
            } catch (RuntimeException failure) {
                for (var checkpoint : checkpoints.entrySet())
                    checkpoint.getKey().restore(checkpoint.getValue());
                throw failure;
            }
        } finally {
            database.lock.unlock();
        }
    }
}

package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.repository.CursorPage;
import com.reactor.cachedb.core.repository.WindowRequest;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TypedSnapshotRepository<ID, V> {
    static <V> TypedSnapshotRepository<String, V> decoding(
            SnapshotRepository raw, com.reactor.cachedb.core.codec.JsonArrayCodec<V> codec) {
        return new DecodedSnapshotRepository<>(raw, codec, SnapshotIdentity.INSTANCE);
    }

    static <ID, V> TypedSnapshotRepository<ID, V> decoding(
            SnapshotRepository raw,
            com.reactor.cachedb.core.codec.JsonArrayCodec<V> codec,
            java.util.function.Function<ID, String> idMapper) {
        return new DecodedSnapshotRepository<>(raw, codec, idMapper);
    }

    SnapshotLookup<V> lookup(ID id);

    default Optional<List<V>> findValuesById(ID id) {
        SnapshotLookup<V> result = lookup(id);
        return result.isPresent() ? Optional.of(result.values()) : Optional.empty();
    }

    Map<ID, SnapshotLookup<V>> lookupAll(Collection<ID> ids);

    CursorPage<SnapshotLookup<V>> findTypedPage(String partitionKey, WindowRequest window);

    SnapshotRefreshResult refreshRoots(Collection<ID> rootIds);
}

package com.reactor.cachedb.spring.boot.snapshot;

import com.reactor.cachedb.core.codec.JsonArrayCodec;
import com.reactor.cachedb.core.repository.CursorPage;
import com.reactor.cachedb.core.repository.WindowRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.function.Function;

final class DecodedSnapshotRepository<ID, V> implements TypedSnapshotRepository<ID, V> {
    private final SnapshotRepository raw;
    private final JsonArrayCodec<V> codec;
    private final Function<ID, String> ids;

    DecodedSnapshotRepository(
            SnapshotRepository raw, JsonArrayCodec<V> codec, Function<ID, String> ids) {
        this.raw = raw;
        this.codec = Objects.requireNonNull(codec, "codec");
        this.ids = Objects.requireNonNull(ids, "idMapper");
    }

    public SnapshotLookup<V> lookup(ID id) {
        return decode(raw.lookupRaw(key(id)));
    }

    public Map<ID, SnapshotLookup<V>> lookupAll(Collection<ID> requested) {
        Objects.requireNonNull(requested, "ids");
        if (requested.size() > 1000)
            throw new IllegalArgumentException("At most 1000 snapshot IDs");
        Map<ID, String> keys = new LinkedHashMap<>();
        for (ID id : requested) keys.put(id, key(id));
        Map<String, SnapshotLookup<String>> values = raw.lookupRawAll(keys.values());
        Map<ID, SnapshotLookup<V>> result = new LinkedHashMap<>();
        for (var entry : keys.entrySet())
            result.put(entry.getKey(), decode(values.get(entry.getValue())));
        return Collections.unmodifiableMap(result);
    }

    public CursorPage<SnapshotLookup<V>> findTypedPage(String partition, WindowRequest window) {
        var page = raw.findPage(partition, window);
        List<SnapshotLookup<V>> values = new ArrayList<>(page.items().size());
        for (var value : page.items()) values.add(decode(raw.describe(value)));
        return new CursorPage<>(values, page.nextCursor());
    }

    public SnapshotRefreshResult refreshRoots(Collection<ID> rootIds) {
        List<String> keys = new ArrayList<>(rootIds.size());
        for (ID id : rootIds) keys.add(key(id));
        return raw.refreshRoots(Collections.unmodifiableList(keys));
    }

    public SnapshotTypedPageReadResult<V> findTypedPage(SnapshotPageRequest request) {
        var page = raw.findPage(request);
        List<SnapshotLookup<V>> values = new ArrayList<>(page.items().size());
        for (var value : page.items()) values.add(decode(raw.describe(value)));
        return new SnapshotTypedPageReadResult<>(values, page.nextCursor());
    }

    private String key(ID id) {
        return Objects.requireNonNull(ids.apply(Objects.requireNonNull(id, "id")), "serialized id");
    }

    private SnapshotLookup<V> decode(SnapshotLookup<String> value) {
        List<V> values = List.of();
        if (value.isPresent()) {
            try {
                values =
                        Objects.requireNonNull(
                                codec.decodeArray(value.rawPayload()),
                                "Snapshot payload must be an array, not null");
            } catch (IOException failure) {
                throw new UncheckedIOException("Invalid snapshot payload: " + value.id(), failure);
            }
        }
        return new SnapshotLookup<>(
                value.id(),
                value.status(),
                values,
                value.rawPayload(),
                value.refreshedAt(),
                value.ageMillis(),
                value.generation());
    }
}

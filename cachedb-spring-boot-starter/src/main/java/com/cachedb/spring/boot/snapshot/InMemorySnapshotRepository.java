package com.reactor.cachedb.spring.boot.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactor.cachedb.core.codec.Utf8Length;
import com.reactor.cachedb.core.repository.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Deterministic consumer fixture; publication and multi-ID reads share one lock. */
public final class InMemorySnapshotRepository implements SnapshotRepository {
    // Opaque fixture generations must be unique across repositories, not cryptographically random.
    private static final AtomicLong GENERATIONS = new AtomicLong();
    private static final Comparator<Map.Entry<String, Row>> PAGE_ORDER = new PageOrder();

    private static final class PageOrder implements Comparator<Map.Entry<String, Row>> {
        @Override
        public int compare(Map.Entry<String, Row> left, Map.Entry<String, Row> right) {
            int score = Double.compare(left.getValue().score, right.getValue().score);
            return score != 0 ? score : left.getKey().compareTo(right.getKey());
        }
    }

    private void revise(String partition) {
        revisions.put(partition, revisions.getOrDefault(partition, 0L) + 1);
    }

    private record Row(
            String payload, long at, String partition, double score, long payloadBytes) {}

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Clock clock;
    private final Duration warnAge;
    private String generation;
    private Function<Collection<String>, Map<String, String>> refresher;

    public InMemorySnapshotRepository() {
        this(Clock.systemUTC(), Duration.ZERO);
    }

    public InMemorySnapshotRepository(Clock clock, Duration warnAge) {
        this.clock = Objects.requireNonNull(clock);
        this.warnAge = Objects.requireNonNull(warnAge);
        if (warnAge.isNegative())
            throw new IllegalArgumentException("warnAge must not be negative");
    }

    public void putSnapshot(String id, String payload) {
        putSnapshot(id, payload, "", 0);
    }

    public void putSnapshot(String id, String payload, String partition, double score) {
        lock.lock();
        try {
            if (id == null || id.isBlank() || id.length() > 512 || id.indexOf('\n') >= 0)
                throw new IllegalArgumentException("Invalid snapshot root id");
            if (!Double.isFinite(score)) throw new IllegalArgumentException("Invalid score");
            if (generation == null)
                generation = "in-memory:".concat(Long.toString(GENERATIONS.incrementAndGet()));
            Row old =
                    rows.put(
                            Objects.requireNonNull(id),
                            new Row(
                                    Objects.requireNonNull(payload),
                                    clock.millis(),
                                    partition,
                                    score,
                                    Utf8Length.of(payload)));
            if (old != null) revise(old.partition);
            if (old == null || !Objects.equals(old.partition, partition)) revise(partition);
        } finally {
            lock.unlock();
        }
    }

    public void expire() {
        lock.lock();
        try {
            generation = null;
            rows.clear();
            revisions.clear();
        } finally {
            lock.unlock();
        }
    }

    public InMemorySnapshotRepository onRefresh(
            Function<Collection<String>, Map<String, String>> refresher) {
        this.refresher = Objects.requireNonNull(refresher);
        return this;
    }

    public <V> TypedSnapshotRepository<String, V> typedRepository(
            ObjectMapper mapper, Class<V> type) {
        return new DecodedSnapshotRepository<>(
                this,
                new JacksonJsonArrayCodec<>(mapper, type).prepared(),
                SnapshotIdentity.INSTANCE);
    }

    public <V> TypedSnapshotRepository<String, V> typedRepository(
            com.reactor.cachedb.core.codec.JsonArrayCodec<V> codec) {
        return new DecodedSnapshotRepository<>(this, codec, SnapshotIdentity.INSTANCE);
    }

    public Optional<SnapshotValue> findById(String id) {
        var value = lookupRaw(id);
        return value.isPresent()
                ? Optional.of(
                        new SnapshotValue(
                                id, value.rawPayload(), value.refreshedAt(), value.generation()))
                : Optional.empty();
    }

    public SnapshotLookup<String> lookupRaw(String id) {
        lock.lock();
        try {
            if (generation == null)
                return new SnapshotLookup<>(
                        id, SnapshotStatus.SNAPSHOT_UNAVAILABLE, List.of(), null, 0, 0, null);
            Row row = rows.get(id);
            return row == null
                    ? new SnapshotLookup<>(
                            id,
                            SnapshotStatus.ABSENT_IN_GENERATION,
                            List.of(),
                            null,
                            0,
                            0,
                            generation)
                    : describe(id, row.payload, row.at, generation);
        } finally {
            lock.unlock();
        }
    }

    public Map<String, SnapshotLookup<String>> lookupRawAll(Collection<String> ids) {
        lock.lock();
        try {
            Map<String, SnapshotLookup<String>> result = SnapshotRepository.super.lookupRawAll(ids);
            long bytes = 0;
            for (var value : result.values()) {
                if (!value.isPresent()) continue;
                Row row = rows.get(value.id());
                bytes +=
                        row != null && row.payload == value.rawPayload()
                                ? row.payloadBytes
                                : Utf8Length.of(value.rawPayload());
            }
            if (bytes > 4L * 1024 * 1024)
                throw new IllegalStateException("Snapshot page byte budget exceeded");
            return result;
        } finally {
            lock.unlock();
        }
    }

    public SnapshotLookup<String> describe(SnapshotValue value) {
        return describe(value.id(), value.payload(), value.refreshedAt(), value.generation());
    }

    private SnapshotLookup<String> describe(
            String id, String payload, long refreshedAt, String activeGeneration) {
        long age = Math.max(0, clock.millis() - refreshedAt);
        return new SnapshotLookup<>(
                id,
                !warnAge.isZero() && age >= warnAge.toMillis()
                        ? SnapshotStatus.STALE_WARNING
                        : SnapshotStatus.FOUND,
                List.of(payload),
                payload,
                refreshedAt,
                age,
                activeGeneration);
    }

    public SnapshotRefreshResult refreshRoots(Collection<String> ids) {
        if (refresher == null) return SnapshotRepository.super.refreshRoots(ids);
        if (ids.isEmpty() || ids.size() > 1000)
            throw new IllegalArgumentException("Invalid root count");
        lock.lock();
        try {
            if (generation == null)
                throw new IllegalStateException(
                        "First complete snapshot is required before partial refresh");
            Map<String, String> changed = refresher.apply(List.copyOf(ids));
            long bytes = 0;
            for (var entry : changed.entrySet()) {
                if (!ids.contains(entry.getKey()) || entry.getValue() == null)
                    throw new IllegalArgumentException(
                            "Refresh fixture returned an undeclared or null root");
                bytes += Utf8Length.of(entry.getValue());
            }
            if (bytes > 4L * 1024 * 1024)
                throw new IllegalArgumentException("Snapshot delta byte budget exceeded");
            for (String id : ids) {
                Row prior = rows.get(id);
                if (changed.containsKey(id))
                    putSnapshot(
                            id,
                            changed.get(id),
                            prior == null ? "" : prior.partition,
                            prior == null ? 0 : prior.score);
                else if (prior != null) {
                    rows.remove(id);
                    revise(prior.partition);
                }
            }
            return new SnapshotRefreshResult("COMPLETED", ids.size(), changed.size(), 0);
        } finally {
            lock.unlock();
        }
    }

    public CursorPage<SnapshotValue> findPage(String partition, WindowRequest window) {
        return page(partition, window, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    public CursorPage<SnapshotValue> findPage(SnapshotPageRequest request) {
        return page(request.partitionKey(), request.window(), request.minScore(), request.maxScore());
    }

    private CursorPage<SnapshotValue> page(String partition, WindowRequest window, double minimum, double maximum) {
        Objects.requireNonNull(window, "window");
        boolean ranged = minimum != Double.NEGATIVE_INFINITY || maximum != Double.POSITIVE_INFINITY;
        String bounds = ranged ? Double.toString(minimum) + "\n" + Double.toString(maximum) : null;
        lock.lock();
        try {
            if (generation == null) return new CursorPage<>(List.of(), null);
            long revision = revisions.getOrDefault(partition, 0L);
            List<Map.Entry<String, Row>> sorted = new ArrayList<>();
            if (rows.size() > 100000) throw new IllegalStateException("In-memory snapshot sort budget exceeded");
            for (var entry : rows.entrySet())
                if (Objects.equals(partition, entry.getValue().partition)
                        && entry.getValue().score >= minimum && entry.getValue().score <= maximum) sorted.add(entry);
            sorted.sort(PAGE_ORDER);
            int start = 0;
            if (window.after() != null) {
                if (window.after().length() > 8192) throw new IllegalArgumentException("Invalid snapshot cursor");
                String[] cursor =
                        new String(
                                        Base64.getUrlDecoder().decode(window.after()),
                                        java.nio.charset.StandardCharsets.UTF_8)
                                .split("\n", -1);
                if ((cursor.length != 4 && cursor.length != 6)
                        || !cursor[0].equals(generation)
                        || !cursor[1].equals(partition)
                        || !cursor[2].equals(Long.toString(revision)))
                    throw new IllegalArgumentException(
                            "Snapshot cursor expired or belongs to another partition");
                if (ranged ? cursor.length != 6 || !(cursor[4] + "\n" + cursor[5]).equals(bounds) : cursor.length != 4)
                    throw new IllegalArgumentException("Snapshot cursor belongs to another score range");
                while (start < sorted.size() && !sorted.get(start).getKey().equals(cursor[3]))
                    start++;
                if (start == sorted.size())
                    throw new IllegalArgumentException("Snapshot cursor member missing");
                start++;
            }
            int end = Math.min(sorted.size(), start + window.limit());
            List<SnapshotValue> page = new ArrayList<>();
            long bytes = 0;
            for (var entry : sorted.subList(start, end)) {
                bytes += entry.getValue().payloadBytes;
                if (bytes > 4L * 1024 * 1024)
                    throw new IllegalStateException("Snapshot page byte budget exceeded");
                page.add(
                        new SnapshotValue(
                                entry.getKey(),
                                entry.getValue().payload,
                                entry.getValue().at,
                                generation));
            }
            String next =
                    end < sorted.size()
                            ? Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(
                                            new StringBuilder()
                                                    .append(generation)
                                                    .append('\n')
                                                    .append(partition)
                                                    .append('\n')
                                                    .append(revision)
                                                    .append('\n')
                                                    .append(sorted.get(end - 1).getKey())
                                                    .append(ranged ? "\n" + bounds : "")
                                                    .toString()
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8))
                            : null;
            return new CursorPage<>(page, next);
        } finally {
            lock.unlock();
        }
    }
}

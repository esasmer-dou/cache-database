package com.reactor.cachedb.redis;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.args.Rawable;
import redis.clients.jedis.util.SafeEncoder;

/** Request-local encoding for the built-in standalone facet key layouts only. */
final class RedisBinaryFacetKeys {
    private final String[] ids;
    private final String[] prefixes;
    private final String tenant;

    static boolean supports(RedisClientOperations operations, RedisKeyStrategy strategy) {
        return operations != null && operations.getClass() == JedisPooledOperations.class
                && !operations.clustered() && supports(strategy);
    }

    static boolean supports(RedisKeyStrategy strategy) {
        return StandardCharsets.UTF_8.equals(SafeEncoder.DEFAULT_CHARSET)
                && strategy != null && (strategy.getClass() == RedisKeyStrategy.class
                || strategy.getClass() == ClusterHashTagKeyStrategy.class);
    }

    RedisBinaryFacetKeys(RedisKeyStrategy strategy, String namespace, List<String> ids, String tenant) {
        if (!supports(strategy)) throw new IllegalArgumentException("Unsupported binary facet key strategy");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty() || ids.size() > 100_000)
            throw new IllegalArgumentException("Facet candidate limit requires 1..100000 identities");
        if (tenant != null && (tenant.isBlank() || tenant.length() > 4096))
            throw new IllegalArgumentException("Owner keys require a tenant of 1..4096 characters");
        this.ids = ids.toArray(String[]::new);
        this.tenant = tenant;
        this.prefixes = tenant == null
                ? new String[] {strategy.entityKey(namespace, ""), strategy.tombstoneKey(namespace, ""),
                        strategy.indexMetaKey(namespace, "")}
                : new String[] {strategy.entityKey(namespace, ""), strategy.tombstoneKey(namespace, ""),
                        strategy.indexMetaKey(namespace, ""), strategy.versionKey(namespace, "")};
    }

    int size() { return ids.length; }

    void requireTenant(String tenant) {
        if (!Objects.equals(this.tenant, tenant)) throw new IllegalArgumentException("Invalid binary facet tenant");
    }

    Prepared prepare(int rows, String groupField, String valueField, long budget, boolean compact) {
        if (rows < 1 || rows > 512) throw new IllegalArgumentException("Invalid facet window");
        // Retain indexBatchKeys' UTF-16 and conservative per-window limits before encoding any key.
        for (int start = 0; start < ids.length; start += rows) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
            long bytes = 3L * (8192 + (tenant == null ? 0 : tenant.length()));
            for (int i = start; i < Math.min(ids.length, start + rows); i++) {
                int length = String.valueOf(ids[i]).length();
                for (int family = 0; family < prefixes.length; family++) {
                    long keyLength = (long) prefixes[family].length() + length + suffix(family).length();
                    if (keyLength > 16_384) throw new IllegalArgumentException("Invalid index batch key");
                    bytes += 3L * keyLength;
                    if (bytes > RedisStandaloneIndexSnapshot.MAX_BYTES)
                        throw new IllegalArgumentException("Index batch key byte budget exceeded");
                }
            }
        }
        // Use one real identity to obtain the canonical Lua/ARGV without copying or forking the script.
        var template = RedisClientOperations.facetIndexCommand(
                List.of(key(0, 0)), List.of(key(1, 0)), List.of(key(2, 0)),
                tenant == null ? null : List.of(key(3, 0)), tenant, groupField, valueField, budget, compact);
        return new Prepared(rows, template.script(), template.arguments());
    }

    private String suffix(int family) { return family == 3 ? ":tenant" : ""; }

    private String key(int family, int index) { return prefixes[family] + ids[index] + suffix(family); }

    final class Prepared {
        private final int rows;
        private final byte[] script;
        private final byte[][] arguments;
        private final byte[][] encodedPrefixes;

        private Prepared(int rows, String script, List<String> arguments) {
            this.rows = rows;
            this.script = SafeEncoder.encode(script);
            this.arguments = new byte[arguments.size()][];
            for (int i = 0; i < arguments.size(); i++) this.arguments[i] = SafeEncoder.encode(arguments.get(i));
            encodedPrefixes = new byte[prefixes.length][];
            for (int i = 0; i < prefixes.length; i++) encodedPrefixes[i] = SafeEncoder.encode(prefixes[i]);
        }

        CommandArguments command(int start, int size) {
            if (start < 0 || start >= ids.length || start % rows != 0
                    || size != Math.min(rows, ids.length - start))
                throw new IllegalArgumentException("Invalid facet window");
            var encodedIds = new byte[size][];
            for (int i = 0; i < size; i++) encodedIds[i] = SafeEncoder.encode(String.valueOf(ids[start + i]));
            return new EncodedCommand(this, encodedIds);
        }

        void independentScripts(List<CommandArguments> commands) {
            for (int i = 0; i < commands.size(); i++) {
                var arguments = commands.get(i).iterator(); arguments.next();
                var independent = new CommandArguments(Command.EVAL);
                // Custom transports may retain Rawables or defer serialization; never expose the streaming cursor.
                while (arguments.hasNext()) independent.add(owned(arguments.next().getRaw().clone()));
                commands.set(i, independent);
            }
        }
    }

    // Only our private immutable plan can supply a charge without materializing its keys.
    static long admissionBytes(CommandArguments command) {
        return command instanceof EncodedCommand encoded ? encoded.admissionBytes : -1;
    }

    /** Only the stock Connection consumes this cursor; it serializes each argument before requesting the next. */
    private static final class EncodedCommand extends CommandArguments {
        private final Prepared prepared;
        private final byte[][] ids;
        private final byte[] keyCount;
        private final byte[] rowCount;
        private final int keys;
        private final long admissionBytes;

        private EncodedCommand(Prepared prepared, byte[][] ids) {
            super(Command.EVAL);
            this.prepared = prepared;
            this.ids = ids;
            keys = Math.multiplyExact(prepared.encodedPrefixes.length, ids.length);
            keyCount = SafeEncoder.encode(Integer.toString(keys));
            rowCount = SafeEncoder.encode(Integer.toString(ids.length));
            long bytes = Command.EVAL.getRaw().length + 16L + prepared.script.length + 16L + keyCount.length + 16L;
            for (byte[] id : ids) {
                for (int family = 0; family < prepared.encodedPrefixes.length; family++)
                    bytes = Math.addExact(bytes, prepared.encodedPrefixes[family].length + (long) id.length
                            + (family == 3 ? 7 : 0) + 16L);
            }
            for (int i = 0; i < prepared.arguments.length; i++)
                bytes = Math.addExact(bytes, (i == 2 ? rowCount.length : prepared.arguments[i].length) + 16L);
            admissionBytes = bytes;
        }

        @Override public int size() { return 3 + keys + prepared.arguments.length; }

        @Override public Iterator<Rawable> iterator() { return new Cursor(); }

        private final class Cursor implements Iterator<Rawable>, Rawable {
            private static final int MAX_SCRATCH_BYTES = 64 * 1024;
            private final byte[][] scratch = new byte[16][];
            private int scratchBytes;
            private int nextSlot;
            private int position = -1;
            private byte[] current;

            @Override public boolean hasNext() { return position + 1 < size(); }

            @Override public Rawable next() {
                if (!hasNext()) throw new NoSuchElementException();
                position++;
                current = null;
                return this;
            }

            @Override public byte[] getRaw() {
                if (current != null) return current;
                if (position < 0) throw new IllegalStateException("Facet cursor has no current argument");
                if (position == 0) return Command.EVAL.getRaw();
                if (position == 1) return prepared.script;
                if (position == 2) return keyCount;
                if (position >= 3 + keys) {
                    int argument = position - 3 - keys;
                    return argument == 2 ? rowCount : prepared.arguments[argument];
                }
                int key = position - 3;
                int family = key / ids.length;
                byte[] id = ids[key % ids.length];
                byte[] prefix = prepared.encodedPrefixes[family];
                byte[] value = buffer(prefix.length + id.length + (family == 3 ? 7 : 0));
                // Built-in prefixes end in ':', so surrogate pairs cannot cross this UTF-8 join.
                System.arraycopy(prefix, 0, value, 0, prefix.length);
                System.arraycopy(id, 0, value, prefix.length, id.length);
                if (family == 3) {
                    int end = prefix.length + id.length;
                    value[end] = ':'; value[end + 1] = 't'; value[end + 2] = 'e'; value[end + 3] = 'n';
                    value[end + 4] = 'a'; value[end + 5] = 'n'; value[end + 6] = 't';
                }
                current = value;
                return value;
            }

            private byte[] buffer(int length) {
                for (byte[] value : scratch) if (value != null && value.length == length) return value;
                // Preserve bounded retention even with adversarially varying identity lengths.
                if (length > MAX_SCRATCH_BYTES) return new byte[length];
                int slot;
                do {
                    slot = nextSlot;
                    if (scratch[slot] != null) { scratchBytes -= scratch[slot].length; scratch[slot] = null; }
                    nextSlot = (nextSlot + 1) % scratch.length;
                } while (scratchBytes + length > MAX_SCRATCH_BYTES);
                byte[] value = new byte[length];
                scratch[slot] = value;
                scratchBytes += length;
                return value;
            }
        }
    }

    private static Rawable owned(byte[] bytes) { return () -> bytes; }
}

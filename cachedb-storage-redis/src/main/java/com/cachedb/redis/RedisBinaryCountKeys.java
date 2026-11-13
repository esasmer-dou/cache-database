package com.reactor.cachedb.redis;

import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.args.Rawable;
import redis.clients.jedis.util.SafeEncoder;

/** Request-local COUNT(*) keys for the built-in standalone UTF-8 layouts only. */
final class RedisBinaryCountKeys {
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

    RedisBinaryCountKeys(RedisKeyStrategy strategy, String namespace, List<String> ids, String tenant) {
        if (!supports(strategy)) throw new IllegalArgumentException("Unsupported binary count key strategy");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) throw new IllegalArgumentException("COUNT requires identities");
        if (tenant != null && (tenant.isBlank() || tenant.length() > 4096))
            throw new IllegalArgumentException("Owner keys require a tenant of 1..4096 characters");
        this.ids = ids.toArray(String[]::new);
        this.tenant = tenant;
        this.prefixes = tenant == null
                ? new String[] {strategy.entityKey(namespace, ""), strategy.tombstoneKey(namespace, "")}
                : new String[] {strategy.entityKey(namespace, ""), strategy.tombstoneKey(namespace, ""),
                        strategy.versionKey(namespace, "")};
    }

    int size() { return ids.length; }

    void requireTenant(String tenant) {
        if (!Objects.equals(this.tenant, tenant)) throw new IllegalArgumentException("Invalid binary count tenant");
    }

    Prepared prepare() {
        // Match indexBatchKeys/Batch.create UTF-16 and conservative window admission before any I/O.
        for (int start = 0; start < ids.length; start += RedisStandaloneIndexSnapshot.MAX_ROWS) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Native snapshot interrupted");
            long bytes = 3L * (8192 + (tenant == null ? 0 : tenant.length()));
            int end = Math.min(ids.length, start + RedisStandaloneIndexSnapshot.MAX_ROWS);
            for (int i = start; i < end; i++) {
                int length = String.valueOf(ids[i]).length();
                for (int family = 0; family < prefixes.length; family++) {
                    long keyLength = (long) prefixes[family].length() + length + suffix(family).length();
                    if (keyLength > 16_384) throw new IllegalArgumentException("Invalid index batch key");
                    bytes = Math.addExact(bytes, 3L * keyLength);
                    if (bytes > RedisStandaloneIndexSnapshot.MAX_BYTES)
                        throw new IllegalArgumentException("Index batch key byte budget exceeded");
                }
            }
        }
        return new Prepared();
    }

    private String suffix(int family) { return family == 2 ? ":tenant" : ""; }
    private String key(int family, int index) { return prefixes[family] + ids[index] + suffix(family); }

    RedisClientOperations.IndexReadKeys window(int start) {
        int size = Math.min(RedisStandaloneIndexSnapshot.MAX_ROWS, ids.length - start);
        requireWindow(start, size);
        return new RedisClientOperations.IndexReadKeys(strings(0, start, size), strings(1, start, size),
                Collections.nCopies(size, null), tenant == null ? null : strings(2, start, size));
    }

    private List<String> strings(int family, int start, int size) {
        return new AbstractList<>() {
            @Override public int size() { return size; }
            @Override public String get(int index) { return key(family, start + Objects.checkIndex(index, size)); }
        };
    }

    private void requireWindow(int start, int size) {
        if (start < 0 || start >= ids.length || start % RedisStandaloneIndexSnapshot.MAX_ROWS != 0
                || size != Math.min(RedisStandaloneIndexSnapshot.MAX_ROWS, ids.length - start))
            throw new IllegalArgumentException("Invalid count window");
    }

    final class Prepared {
        private final byte[][] encodedPrefixes;
        private final int ownerEnd;
        private Rawable script;
        private List<String> arguments;

        private Prepared() {
            encodedPrefixes = new byte[prefixes.length][];
            for (int i = 0; i < prefixes.length; i++) encodedPrefixes[i] = SafeEncoder.encode(prefixes[i]);
            ownerEnd = tenant == null ? 0 : tenant.getBytes(StandardCharsets.UTF_8).length;
        }

        private byte[][] keys(int start, int size) {
            requireWindow(start, size);
            var keys = new byte[prefixes.length * size][];
            for (int i = 0; i < size; i++) {
                String id = String.valueOf(ids[start + i]);
                // Encode ASCII into the first final key once; retain SafeEncoder's Unicode replacement behavior.
                byte[] encodedId = null;
                for (int j = 0; j < id.length(); j++) {
                    if (id.charAt(j) > 0x7f) {
                        encodedId = SafeEncoder.encode(id);
                        break;
                    }
                }
                int idLength = encodedId == null ? id.length() : encodedId.length;
                for (int family = 0; family < prefixes.length; family++) {
                    byte[] prefix = encodedPrefixes[family];
                    // Built-in prefixes and the owner suffix have ':' barriers at UTF-8 joins.
                    byte[] key = new byte[prefix.length + idLength + (family == 2 ? 7 : 0)];
                    System.arraycopy(prefix, 0, key, 0, prefix.length);
                    if (encodedId != null) System.arraycopy(encodedId, 0, key, prefix.length, idLength);
                    else if (family == 0) {
                        for (int j = 0; j < idLength; j++) key[prefix.length + j] = (byte) id.charAt(j);
                    } else System.arraycopy(keys[i], encodedPrefixes[0].length, key, prefix.length, idLength);
                    if (family == 2) {
                        int end = prefix.length + idLength;
                        key[end] = ':'; key[end + 1] = 't'; key[end + 2] = 'e'; key[end + 3] = 'n';
                        key[end + 4] = 'a'; key[end + 5] = 'n'; key[end + 6] = 't';
                    }
                    keys[family * size + i] = key;
                }
            }
            return keys;
        }

        CommandArguments evalCommand(int start, int size) {
            byte[][] keys = keys(start, size);
            if (script == null) {
                var template = RedisClientOperations.indexAggregateCommand(List.of(key(0, 0)), List.of(key(1, 0)),
                        Collections.singletonList(null), tenant == null ? null : List.of(key(2, 0)), tenant, null, true);
                script = owned(SafeEncoder.encode(template.script()));
                arguments = template.arguments();
            }
            var command = new CommandArguments(Command.EVAL).add(script).add(keys.length);
            for (byte[] key : keys) command.add(owned(key));
            for (int i = 0; i < arguments.size(); i++)
                command.add(owned(SafeEncoder.encode(i == 2 ? Integer.toString(size) : arguments.get(i))));
            return command;
        }

        RedisStandaloneIndexSnapshot.Phase nativeCommands(int start, int size) {
            byte[][] keys = keys(start, size);
            var transaction = new RedisStandaloneIndexSnapshot.Phase();
            transaction.add(new CommandArguments(Command.MULTI));
            var entities = new CommandArguments(Command.EXISTS);
            var tombstones = new CommandArguments(Command.EXISTS);
            for (int i = 0; i < size; i++) {
                entities.add(owned(keys[i]));
                tombstones.add(owned(keys[size + i]));
            }
            transaction.add(entities);
            transaction.add(tombstones);
            if (tenant != null)
                transaction.addBoundedOwners(Arrays.copyOfRange(keys, 2 * size, 3 * size), ownerEnd);
            transaction.add(new CommandArguments(Command.EXEC));
            return transaction;
        }

        void independentScripts(List<CommandArguments> commands) {
            for (int i = 0; i < commands.size(); i++) {
                var arguments = commands.get(i).iterator(); arguments.next();
                var independent = new CommandArguments(Command.EVAL).add(owned(arguments.next().getRaw().clone()));
                while (arguments.hasNext()) independent.add(arguments.next());
                commands.set(i, independent);
            }
        }
    }

    private static Rawable owned(byte[] bytes) { return () -> bytes; }
}

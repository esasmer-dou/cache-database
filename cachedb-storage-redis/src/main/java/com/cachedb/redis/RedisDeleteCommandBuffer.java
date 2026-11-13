package com.reactor.cachedb.redis;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import redis.clients.jedis.*;

/** In-memory adapter: reuse the canonical index-removal planner, never issue network I/O. */
final class RedisDeleteCommandBuffer extends AbstractPipeline {
    static final int MAX_COMMANDS = 8192;
    static final long MAX_BYTES = 16L * 1024 * 1024;
    private final List<List<String>> commands = new ArrayList<>();
    private long bytes;

    RedisDeleteCommandBuffer() { super(new CommandObjects()); }
    @Override protected <V> Response<V> appendCommand(CommandObject<V> command) {
        var args = new ArrayList<String>();
        for (var value : command.getArguments()) {
            byte[] raw = value.getRaw();
            if (raw.length > MAX_BYTES - bytes) throw new IllegalArgumentException("Delete command byte budget exceeded");
            bytes += raw.length;
            try {
                args.add(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString());
            } catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid index command encoding"); }
        }
        if (commands.size() == MAX_COMMANDS || args.size() < 2 || args.size() > 4
                || !Set.of("DEL", "SREM", "ZREM", "SADD", "SET").contains(args.get(0)))
            throw new IllegalArgumentException("Unsupported bounded delete maintenance command");
        commands.add(List.copyOf(args));
        return new Response<>(command.getBuilder());
    }
    List<List<String>> commands() { return List.copyOf(commands); }
    @Override public void sync() { }
    @Override public void close() { }
}

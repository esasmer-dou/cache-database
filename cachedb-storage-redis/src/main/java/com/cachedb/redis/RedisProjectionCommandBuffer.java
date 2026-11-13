package com.reactor.cachedb.redis;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import redis.clients.jedis.*;

/** Memory-only capture of the existing projection index planner. */
final class RedisProjectionCommandBuffer extends AbstractPipeline {
    static final int MAX_COMMANDS=8192, MAX_ARITY=2050;
    static final long MAX_BYTES=16L*1024*1024;
    private static final Set<String> ALLOWED=Set.of("DEL","SET","SETEX","SADD","SREM","ZADD","ZREM","HSET");
    private final List<List<String>> commands=new ArrayList<>();
    private long bytes;
    RedisProjectionCommandBuffer(){super(new CommandObjects());}
    @Override protected <V> Response<V> appendCommand(CommandObject<V> command){
        if(commands.size()>=MAX_COMMANDS)throw new IllegalArgumentException("Projection command count exceeds 8192");
        var args=new ArrayList<String>();
        for(var value:command.getArguments()){
            if(args.size()>=MAX_ARITY)throw new IllegalArgumentException("Projection command arity exceeded");
            byte[] raw=value.getRaw();
            if(raw.length>MAX_BYTES-bytes)throw new IllegalArgumentException("Projection command bytes exceed 16 MiB");
            bytes+=raw.length;
            try{
                args.add(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString());
            }catch(CharacterCodingException invalid){throw new IllegalArgumentException("Invalid projection command encoding");}
        }
        if(args.size()<2||!ALLOWED.contains(args.get(0)))throw new IllegalArgumentException("Unsupported projection command");
        commands.add(List.copyOf(args));return new Response<>(command.getBuilder());
    }
    List<List<String>> commands(){return List.copyOf(commands);}
    @Override public void sync(){}
    @Override public void close(){}
}

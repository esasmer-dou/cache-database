package com.reactor.cachedb.redis;

import java.nio.charset.StandardCharsets;
import java.util.*;
import redis.clients.jedis.Protocol.Command;

/** Bounded control-plane parsing; never invoked on the L1 read path. */
final class RedisTrackingTopology {
    record View(String fingerprint, Set<String> masters) {}
    static String text(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        if (value instanceof String string) return string;
        throw new IllegalStateException("Unexpected Redis control reply");
    }
    static long id(RedisDedicatedConnection socket) {
        Object value = socket.command(Command.CLIENT, "ID");
        if (!(value instanceof Long id) || id < 1) throw new IllegalStateException("Invalid Redis client ID");
        return id;
    }
    static boolean master(RedisDedicatedConnection socket) {
        Object value = socket.command(Command.ROLE);
        if (!(value instanceof List<?> role) || role.isEmpty() || role.size() > 8)
            throw new IllegalStateException("Invalid Redis ROLE");
        return "master".equals(text(role.get(0)));
    }
    static String nodeId(RedisDedicatedConnection socket) {
        String id = text(socket.command(Command.CLUSTER, "MYID"));
        if (!id.matches("[a-fA-F0-9]{40}")) throw new IllegalStateException("Invalid Redis node ID");
        return id;
    }
    static View view(RedisDedicatedConnection socket, int maxNodes, int maxMasters) {
        return parse(text(socket.command(Command.CLUSTER, "NODES")), maxNodes, maxMasters);
    }
    static View parse(String value, int maxNodes, int maxMasters) {
        if (value.length() > 131072) throw new IllegalStateException("Cluster topology exceeds 128 KiB");
        String[] lines = value.split("\\r?\\n");
        if (lines.length > maxNodes) throw new IllegalStateException("Cluster node budget exceeded");
        var masters = new TreeMap<String, String>();
        var seen = new HashSet<String>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            String[] fields = line.trim().split(" +", 9);
            if (fields.length < 8 || !fields[0].matches("[a-fA-F0-9]{40}") || !seen.add(fields[0]))
                throw new IllegalStateException("Malformed cluster topology");
            Set<String> flags = Set.of(fields[2].split(","));
            if (!flags.contains("master")) continue;
            if (flags.contains("fail") || flags.contains("fail?") || flags.contains("noaddr")
                    || flags.contains("handshake") || !"connected".equals(fields[7]))
                throw new IllegalStateException("Cluster master is not healthy");
            if (masters.size() >= maxMasters) throw new IllegalStateException("Cluster master budget exceeded");
            // Ignore gossip timestamps and the reporting node's 'myself' flag.
            masters.put(fields[0], fields[6] + ":" + (fields.length == 9 ? fields[8] : ""));
        }
        if (masters.isEmpty()) throw new IllegalStateException("No cluster masters");
        var fingerprint = new StringBuilder();
        masters.forEach((id, slots) -> fingerprint.append(id).append('=').append(slots).append(';'));
        return new View(fingerprint.toString(), Set.copyOf(masters.keySet()));
    }
    private RedisTrackingTopology() {}
}

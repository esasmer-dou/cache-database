package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.args.Rawable;
import redis.clients.jedis.util.RedisOutputStream;

class RedisBinaryFacetKeysTest {
    @Test void asciiKeysMatchCanonicalWireAcrossOwnershipAndFacetModes() throws IOException {
        var ids = new ArrayList<String>();
        ids.add(""); ids.add(null);
        for (int i = 0; i < 525; i++) ids.add(String.valueOf((char) (i % 128)) + ":id:" + i);
        assertCanonicalKeyWindows(ids);
    }

    @Test void unicodeAfterLongAsciiPrefixesRetainsCanonicalReplacementWire() throws IOException {
        var ids = new ArrayList<>(java.util.Collections.nCopies(512, "ascii"));
        for (String tail : List.of("\u0080", "\u00e9", "\u4e00", "\ud83d\ude80", "\ud800", "\udc00", "\ud800x\udc00"))
            ids.add("a".repeat(4096) + tail);
        assertCanonicalKeyWindows(ids);
    }

    @Test void binaryArgumentsMatchCanonicalStringCommandsForEveryBuiltInLayout() {
        var unusual = Arrays.asList("", "a:b", "{other}", "a\u0000b", "\u00c7\u0131\u011f",
                "\uD83D\uDE80", "\uD800", "\uDC00", null);
        var ids = new ArrayList<String>();
        for (int i = 0; i < 527; i++) ids.add(unusual.get(i % unusual.size()));
        for (var strategy : List.of(new RedisKeyStrategy(),
                new RedisKeyStrategy("p\uD800", "e\uDC00", "page", "v", "t", "hot", "ix", "compact"),
                new ClusterHashTagKeyStrategy("prefix", "scope"), new ClusterHashTagKeyStrategy("prefix"))) {
            for (String tenant : Arrays.asList(null, "\u00c7\u0131\u011f")) {
                for (boolean compact : List.of(false, true)) {
                    for (String value : compact ? Arrays.asList((String) null) : Arrays.asList(null, "amount")) {
                        int rows = value == null ? 512 : 256;
                        var keys = new RedisBinaryFacetKeys(strategy, "ns:projection:p\uD800", ids, tenant);
                        var prepared = keys.prepare(rows, "category", value, 12345, compact);
                        for (int start = 0; start < ids.size(); start += rows) {
                            int size = Math.min(rows, ids.size() - start);
                            var expected = canonical(strategy, "ns:projection:p\uD800", ids.subList(start, start + size),
                                    tenant, "category", value, 12345, compact);
                            assertWireEquals(expected, prepared.command(start, size));
                        }
                    }
                }
            }
        }
    }

    @Test void onlyExactStrategiesAndBuiltInOperationsAreEligible() {
        assertTrue(RedisBinaryFacetKeys.supports(new RedisKeyStrategy()));
        assertTrue(RedisBinaryFacetKeys.supports(new ClusterHashTagKeyStrategy("p", "scope")));
        var custom = new RedisKeyStrategy() {
            @Override public String entityKey(String namespace, Object id) { throw new AssertionError("No custom key call"); }
        };
        assertFalse(RedisBinaryFacetKeys.supports(custom));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisBinaryFacetKeys(custom, "ns", List.of("id"), null));
        for (boolean clustered : List.of(false, true)) {
            var operations = new RedisClientOperations() {
                @Override public UnifiedJedis commands() { throw new AssertionError("No custom transport call"); }
                @Override public boolean clustered() { return clustered; }
            };
            assertFalse(RedisBinaryFacetKeys.supports(operations, new RedisKeyStrategy()));
        }
        assertFalse(RedisBinaryFacetKeys.supports(null, new RedisKeyStrategy()));
    }

    @Test void finalKeyArraysAreIndependentAndCustomScriptCopiesCannotCorruptLaterCommands() {
        var keys = new RedisBinaryFacetKeys(new RedisKeyStrategy(), "ns", List.of("same", "same"), "a");
        var prepared = keys.prepare(1, "category", null, 1024, true);
        var commands = new ArrayList<CommandArguments>(List.of(prepared.command(0, 1), prepared.command(1, 1)));
        prepared.independentScripts(commands);
        var first = arguments(commands.getFirst());
        var second = arguments(commands.getLast());
        var seen = new IdentityHashMap<byte[], Boolean>();
        for (var command : commands) {
            var args = arguments(command);
            for (int i = 1; i < args.size(); i++) assertNull(seen.put(args.get(i), Boolean.TRUE));
        }
        byte[] script = second.get(1).clone();
        byte[] entity = second.get(3).clone();
        first.get(1)[0] ^= 1;
        first.get(3)[0] ^= 1;
        assertArrayEquals(script, second.get(1));
        assertArrayEquals(entity, second.get(3));
        assertArrayEquals(entity, snapshots(prepared.command(0, 1)).get(3));
        assertArrayEquals(script, snapshots(prepared.command(0, 1)).get(1));
    }

    @Test void candidateIdentitiesAreFrozenBeforePreparation() {
        var ids = new ArrayList<>(List.of("first", "second"));
        var strategy = new RedisKeyStrategy();
        var keys = new RedisBinaryFacetKeys(strategy, "ns", ids, null);
        ids.set(0, "changed"); ids.clear();
        assertEquals(2, keys.size());
        assertWireEquals(canonical(strategy, "ns", List.of("first", "second"), null, "g", null, 1024, true),
                keys.prepare(512, "g", null, 1024, true).command(0, 2));
    }

    @Test void packedCommandsPreserveCanonicalRespAndFullPhaseAdmissionAcrossEveryWindow() throws IOException {
        var ids = new ArrayList<String>();
        for (int i = 0; i < 10_000; i++) ids.add("id:" + i + "\uD800:\uD83D\uDE80");
        var strategy = new RedisKeyStrategy();
        for (String tenant : Arrays.asList(null, "\u015firket")) {
            for (boolean compact : List.of(false, true)) {
                String value = compact ? null : "amount";
                int rows = compact ? 512 : 256;
                var prepared = new RedisBinaryFacetKeys(strategy, "ns", ids, tenant)
                        .prepare(rows, "g", value, 12345, compact);
                var actual = new ArrayList<CommandArguments>();
                var expected = new ArrayList<CommandArguments>();
                var phase = new RedisStandaloneIndexSnapshot.Phase();
                for (int start = 0; start < ids.size(); start += rows) {
                    int size = Math.min(rows, ids.size() - start);
                    var command = prepared.command(start, size);
                    phase.add(command); // Admission charges the complete command without constructing key buffers.
                    actual.add(command);
                    expected.add(canonical(strategy, "ns", ids.subList(start, start + size),
                            tenant, "g", value, 12345, compact));
                    assertTrue(command.getKeys().isEmpty());
                }
                assertEquals((ids.size() + rows - 1) / rows, phase.size());
                assertArrayEquals(serialize(expected), serialize(actual));
                assertArrayEquals(serialize(actual), serialize(actual), "Serialization must not consume the plan");
            }
        }
    }

    @Test void customMaterializationKeepsRetainedRawablesStableAndEveryArgumentIndependent() {
        var prepared = new RedisBinaryFacetKeys(new RedisKeyStrategy(), "ns", List.of("id", "id2"), "a")
                .prepare(1, "g", "amount", 12345, false);
        var commands = new ArrayList<CommandArguments>(List.of(prepared.command(0, 1), prepared.command(1, 1)));
        var expected = commands.stream().map(RedisBinaryFacetKeysTest::snapshots).toList();
        prepared.independentScripts(commands);
        var seen = new IdentityHashMap<byte[], Boolean>();
        for (int i = 0; i < commands.size(); i++) {
            var retained = new ArrayList<Rawable>();
            for (Rawable argument : commands.get(i)) retained.add(argument);
            for (int j = 0; j < retained.size(); j++) {
                assertArrayEquals(expected.get(i).get(j), retained.get(j).getRaw());
                if (j > 0) assertNull(seen.put(retained.get(j).getRaw(), Boolean.TRUE));
            }
        }
        var first = arguments(commands.getFirst());
        for (int i = 1; i < first.size(); i++) Arrays.fill(first.get(i), (byte) '!');
        var second = arguments(commands.getLast());
        var future = snapshots(prepared.command(1, 1));
        for (int i = 0; i < second.size(); i++) {
            assertArrayEquals(expected.getLast().get(i), second.get(i));
            assertArrayEquals(expected.getLast().get(i), future.get(i));
        }
    }

    @Test void exactUtf16KeyLimitAndConservativeWindowBudgetAreNotRelaxed() {
        var strategy = new RedisKeyStrategy();
        int maxPrefix = Math.max(strategy.entityKey("ns", "").length(),
                Math.max(strategy.tombstoneKey("ns", "").length(), strategy.indexMetaKey("ns", "").length()));
        var allowed = List.of("\uD83D\uDE80".repeat((16_384 - maxPrefix) / 2)
                + "x".repeat((16_384 - maxPrefix) % 2));
        assertWireEquals(canonical(strategy, "ns", allowed, null, "g", null, 1024, true),
                new RedisBinaryFacetKeys(strategy, "ns", allowed, null).prepare(1, "g", null, 1024, true).command(0, 1));
        var rejected = List.of(allowed.getFirst() + "x");
        assertThrows(IllegalArgumentException.class, () -> canonical(strategy, "ns", rejected, null, "g", null, 1024, true));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisBinaryFacetKeys(strategy, "ns", rejected, null).prepare(1, "g", null, 1024, true));
        int prefixSum = strategy.entityKey("ns", "").length() + strategy.tombstoneKey("ns", "").length()
                + strategy.indexMetaKey("ns", "").length();
        int length = (int) ((RedisStandaloneIndexSnapshot.MAX_BYTES / 3 - 8192 - 512L * prefixSum) / (512L * 3));
        for (boolean over : List.of(false, true)) {
            var ids = java.util.Collections.nCopies(512, "x".repeat(length + (over ? 1 : 0)));
            if (over) {
                assertThrows(IllegalArgumentException.class, () -> canonical(strategy, "ns", ids, null, "g", null, 1024, true));
                assertThrows(IllegalArgumentException.class,
                        () -> new RedisBinaryFacetKeys(strategy, "ns", ids, null).prepare(512, "g", null, 1024, true));
            } else {
                assertWireEquals(canonical(strategy, "ns", ids, null, "g", null, 1024, true),
                        new RedisBinaryFacetKeys(strategy, "ns", ids, null).prepare(512, "g", null, 1024, true).command(0, 512));
            }
        }
    }

    @Test void fieldTenantAndWindowContractsRemainStrict() {
        var strategy = new RedisKeyStrategy();
        for (String tenant : List.of("", " ", "x".repeat(4097)))
            assertThrows(IllegalArgumentException.class, () -> new RedisBinaryFacetKeys(strategy, "ns", List.of("id"), tenant));
        var keys = new RedisBinaryFacetKeys(strategy, "ns", List.of("id", "id2"), "a");
        assertThrows(IllegalArgumentException.class, () -> keys.requireTenant("b"));
        assertThrows(IllegalArgumentException.class, () -> keys.prepare(512, "g", "value", 1024, true));
        assertThrows(IllegalArgumentException.class, () -> keys.prepare(512, "g", null, 0, true));
        assertThrows(IllegalArgumentException.class, () -> keys.prepare(512, "x".repeat(4097), null, 1024, true));
        var prepared = keys.prepare(1, "g", null, 1024, true);
        assertThrows(IllegalArgumentException.class, () -> prepared.command(0, 2));
        assertThrows(IllegalArgumentException.class, () -> prepared.command(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> prepared.command(2, 1));
    }

    @Test void streamingKeysReuseExactLengthBuffersAndRepeatedGetRawIsStable() throws IOException {
        var ids = java.util.Collections.nCopies(100, "abc");
        var strategy = new RedisKeyStrategy();
        var prepared = new RedisBinaryFacetKeys(strategy, "ns", ids, "tenant")
                .prepare(512, "g", null, 12345, true);
        var command = prepared.command(0, ids.size());
        var expected = snapshots(canonical(strategy, "ns", ids, "tenant", "g", null, 12345, true));
        var buffers = new IdentityHashMap<byte[], Boolean>();
        int position = 0;
        for (Rawable argument : command) {
            byte[] raw = argument.getRaw();
            assertSame(raw, argument.getRaw());
            assertArrayEquals(expected.get(position), raw, "argument " + position);
            if (position >= 3 && position < 3 + ids.size() * 4) buffers.put(raw, Boolean.TRUE);
            position++;
        }
        assertEquals(command.size(), position);
        assertTrue(buffers.size() <= 4, "Same-length keys must not allocate a full array per identity");
        assertArrayEquals(serialize(List.of(canonical(strategy, "ns", ids, "tenant", "g", null, 12345, true))),
                serialize(List.of(command)));
    }

    @Test void simultaneousIteratorsAndOutOfOrderWindowsRemainIndependent() throws IOException {
        var ids = Arrays.asList("x", "\uD83D\uDE80", null, "longer", "\uD800", "", "\uDC00");
        var strategy = new RedisKeyStrategy();
        var prepared = new RedisBinaryFacetKeys(strategy, "ns:\uD800", ids, "tenant")
                .prepare(2, "g", null, 12345, true);
        var command = prepared.command(0, 2);
        var expected = snapshots(canonical(strategy, "ns:\uD800", ids.subList(0, 2), "tenant", "g", null, 12345, true));
        var first = command.iterator();
        var second = command.iterator();
        int position = 0;
        while (first.hasNext()) {
            Rawable a = first.next();
            byte[] before = a.getRaw().clone();
            Rawable b = second.next();
            assertArrayEquals(expected.get(position), before);
            assertArrayEquals(before, b.getRaw());
            assertArrayEquals(before, a.getRaw());
            if (position >= 3 && position < 11) assertNotSame(a.getRaw(), b.getRaw());
            position++;
        }
        assertFalse(second.hasNext());
        assertThrows(java.util.NoSuchElementException.class, first::next);
        var actual = new ArrayList<CommandArguments>();
        var canonical = new ArrayList<CommandArguments>();
        for (int start : List.of(6, 2, 0, 4, 0)) {
            int size = Math.min(2, ids.size() - start);
            actual.add(prepared.command(start, size));
            canonical.add(canonical(strategy, "ns:\uD800", ids.subList(start, start + size), "tenant", "g", null, 12345, true));
        }
        assertArrayEquals(serialize(canonical), serialize(actual));
        assertArrayEquals(serialize(canonical), serialize(actual));
    }

    @Test void variableLengthsAndScratchEvictionPreserveCanonicalBytesInEitherIdOrder() throws IOException {
        var ids = new ArrayList<String>();
        for (int length = 0; length < 40; length++) ids.add("\u4e00".repeat(length * 120) + "\uD800");
        ids.add(null); ids.add(""); ids.add("\uD83D\uDE80");
        var strategy = new ClusterHashTagKeyStrategy("p", "scope");
        for (int order = 0; order < 2; order++) {
            for (String tenant : Arrays.asList(null, "tenant")) {
                var prepared = new RedisBinaryFacetKeys(strategy, "ns", ids, tenant)
                        .prepare(512, "g", "amount", 12345, false);
                var expected = canonical(strategy, "ns", ids, tenant, "g", "amount", 12345, false);
                assertWireEquals(expected, prepared.command(0, ids.size()));
                assertArrayEquals(serialize(List.of(expected)), serialize(List.of(prepared.command(0, ids.size()))));
            }
            java.util.Collections.reverse(ids);
        }
    }

    @Test void precomputedAdmissionMatchesCanonicalArgumentChargeWithoutRelaxingThePhaseLimit() {
        var strategy = new RedisKeyStrategy();
        var ids = Arrays.asList(null, "", "ascii", "\uD800", "\uDC00", "\uD83D\uDE80", "\u4e00");
        for (String tenant : Arrays.asList(null, "\u015firket")) {
            for (boolean compact : List.of(false, true)) {
                String value = compact ? null : "amount";
                var command = new RedisBinaryFacetKeys(strategy, "ns:\uD800", ids, tenant)
                        .prepare(512, "g", value, 12345, compact).command(0, ids.size());
                var original = canonical(strategy, "ns:\uD800", ids, tenant, "g", value, 12345, compact);
                long expected = snapshots(original).stream().mapToLong(bytes -> bytes.length + 16L).sum();
                assertEquals(expected, RedisBinaryFacetKeys.admissionBytes(command));
                assertEquals(-1, RedisBinaryFacetKeys.admissionBytes(original));
                for (boolean over : List.of(false, true)) {
                    var phase = new RedisStandaloneIndexSnapshot.Phase();
                    int padding = Math.toIntExact(RedisStandaloneIndexSnapshot.MAX_BYTES - expected - 36 + (over ? 1 : 0));
                    byte[] bytes = new byte[padding];
                    phase.add(new CommandArguments(Protocol.Command.ECHO).add((Rawable) () -> bytes));
                    if (over) {
                        var failure = assertThrows(RuntimeException.class, () -> phase.add(command));
                        assertEquals("NativeBudgetUnavailable", failure.getClass().getSimpleName());
                        assertEquals(1, phase.size());
                    } else {
                        phase.add(command);
                        assertEquals(2, phase.size());
                        assertWireEquals(original, phase.commandAt(1));
                    }
                }
            }
        }
    }

    @Test void ordinaryCommandsStillUseTheirLiveCheckedArgumentAccounting() {
        int[] reads = {0};
        var command = new CommandArguments(Protocol.Command.ECHO).add((Rawable) () -> {
            reads[0]++;
            return new byte[3];
        });
        assertEquals(-1, RedisBinaryFacetKeys.admissionBytes(command));
        var phase = new RedisStandaloneIndexSnapshot.Phase();
        phase.add(command);
        assertEquals(1, reads[0]);
        assertEquals(1, phase.size());
    }

    private static void assertCanonicalKeyWindows(List<String> ids) throws IOException {
        String namespace = "ns:\u4e00:\ud800";
        for (var strategy : List.of(new RedisKeyStrategy(), new ClusterHashTagKeyStrategy("p", "scope"))) {
            for (String tenant : Arrays.asList(null, "\u00c7\u0131\u011f")) {
                for (int mode = 0; mode < 3; mode++) {
                    boolean compact = mode == 0;
                    String value = mode == 2 ? "amount" : null;
                    int rows = value == null ? 512 : 256;
                    var prepared = new RedisBinaryFacetKeys(strategy, namespace, ids, tenant)
                            .prepare(rows, "g", value, 12345, compact);
                    for (int start = 0; start < ids.size(); start += rows) {
                        int size = Math.min(rows, ids.size() - start);
                        var expected = canonical(strategy, namespace, ids.subList(start, start + size),
                                tenant, "g", value, 12345, compact);
                        var actual = prepared.command(start, size);
                        assertWireEquals(expected, actual);
                        assertArrayEquals(serialize(List.of(expected)), serialize(List.of(actual)));
                    }
                }
            }
        }
    }

    static CommandArguments canonical(RedisKeyStrategy strategy, String namespace, List<String> ids, String tenant,
            String group, String value, long budget, boolean compact) {
        var command = RedisClientOperations.facetIndexCommand(
                ids.stream().map(id -> strategy.entityKey(namespace, id)).toList(),
                ids.stream().map(id -> strategy.tombstoneKey(namespace, id)).toList(),
                ids.stream().map(id -> strategy.indexMetaKey(namespace, id)).toList(),
                tenant == null ? null : ids.stream().map(id -> strategy.versionKey(namespace, id) + ":tenant").toList(),
                tenant, group, value, budget, compact);
        return RedisStandaloneIndexSnapshot.evalCommand(command.script(), command.keys(), command.arguments());
    }

    static List<byte[]> arguments(CommandArguments command) {
        var args = new ArrayList<byte[]>();
        for (var argument : command) args.add(argument.getRaw());
        return args;
    }

    private static List<byte[]> snapshots(CommandArguments command) {
        var args = new ArrayList<byte[]>();
        for (Rawable argument : command) args.add(argument.getRaw().clone());
        return args;
    }

    private static byte[] serialize(List<CommandArguments> commands) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var stream = new RedisOutputStream(output)) {
            for (var command : commands) Protocol.sendCommand(stream, command);
            stream.flush();
        }
        return output.toByteArray();
    }

    private static void assertWireEquals(CommandArguments expected, CommandArguments actual) {
        assertEquals(expected.size(), actual.size());
        assertEquals(expected.getCommand(), actual.getCommand());
        var left = arguments(expected);
        var right = snapshots(actual);
        for (int i = 0; i < left.size(); i++) assertArrayEquals(left.get(i), right.get(i), "argument " + i);
    }
}

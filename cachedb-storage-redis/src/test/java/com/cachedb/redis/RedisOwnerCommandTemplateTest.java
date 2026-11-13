package com.reactor.cachedb.redis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.Connection;
import redis.clients.jedis.util.Pool;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.args.RawableFactory;
import redis.clients.jedis.util.RedisInputStream;
import redis.clients.jedis.util.RedisOutputStream;

class RedisOwnerCommandTemplateTest {
    @Test void binaryOwnedCountKeepsLegacyWireBudgetAndUtf8OwnerEndpoint() throws Exception {
        var unusual = Arrays.asList("", "a:b", "{other}", "a\u0000b", "\u00c7\u0131\u011f",
                "\ud83d\ude80", "\ud800", "\udc00", null);
        var ids = IntStream.range(0, 10_000).mapToObj(i -> unusual.get(i % unusual.size())).toList();
        for (var strategy : List.of(new RedisKeyStrategy(),
                new RedisKeyStrategy("p\ud800", "e\udc00", "page", "v", "t", "hot", "ix", "compact"),
                new ClusterHashTagKeyStrategy("prefix", "scope"), new ClusterHashTagKeyStrategy("prefix"))) {
            for (String tenant : List.of("a", "\u015firket:\u4e00:\ud83d\ude80")) {
                String namespace = "ns:projection:p\ud800";
                var prepared = new RedisBinaryCountKeys(strategy, namespace, ids, tenant).prepare();
                for (int start = 0; start < ids.size(); start += 512) {
                    var window = ids.subList(start, Math.min(ids.size(), start + 512));
                    var expected = countTransaction(
                            window.stream().map(id -> strategy.entityKey(namespace, id)).toList(),
                            window.stream().map(id -> strategy.tombstoneKey(namespace, id)).toList(),
                            window.stream().map(id -> strategy.versionKey(namespace, id) + ":tenant").toList(),
                            tenant, false);
                    var actual = prepared.nativeCommands(start, window.size());
                    assertEquals(window.size() + 4, actual.size());
                    assertArrayEquals(serialize(expected), serialize(actual));
                    assertEquals(phaseBytes(expected), phaseBytes(actual));
                    assertSame(actual.commands.get(3), actual.commands.get(window.size() + 2));
                    assertArrayEquals(bytes(Integer.toString(bytes(tenant).length)), argumentBytes(actual, 3, 3));
                }
            }
        }
    }

    @Test void binaryOwnedCountCursorKeepsWindowsAndRequestsIndependent() throws Exception {
        var strategy = new RedisKeyStrategy();
        var ids = IntStream.range(0, 1025).mapToObj(i -> "id:\u015firket:" + i).toList();
        var prepared = new RedisBinaryCountKeys(strategy, "ns", ids, "a").prepare();
        var first = prepared.nativeCommands(0, 512);
        var second = prepared.nativeCommands(512, 512);
        var tail = prepared.nativeCommands(1024, 1);
        var anotherRequest = new RedisBinaryCountKeys(strategy, "ns", ids, "a").prepare().nativeCommands(0, 512);
        byte[] firstWire = serialize(first), secondWire = serialize(second), tailWire = serialize(tail);
        assertArrayEquals(firstWire, serialize(anotherRequest));
        assertArrayEquals(firstWire, serialize(first));
        assertNotSame(first.commands.get(3), second.commands.get(3));
        assertNotSame(first.commands.get(3), anotherRequest.commands.get(3));
        byte[] firstOwner = argumentBytes(first, 3, 1);
        assertNotSame(firstOwner, argumentBytes(anotherRequest, 3, 1));
        assertArrayEquals(bytes(strategy.versionKey("ns", ids.get(512)) + ":tenant"), argumentBytes(second, 3, 1));
        assertEquals(5, tail.size());
        Arrays.fill(firstOwner, (byte) 'x');
        assertArrayEquals(secondWire, serialize(second));
        assertArrayEquals(tailWire, serialize(tail));
        assertArrayEquals(firstWire, serialize(anotherRequest));
        assertThrows(IllegalStateException.class, () -> first.addBoundedOwners(new byte[][] {bytes("other")}, 1));
    }

    @Test void sdkSerializationEmitsEveryDistinctUtf8KeyFromOneCommandTemplate() throws Exception {
        var keys = IntStream.range(0, 512).mapToObj(i -> "owner:\u015firket:" + i).toList();
        var phase = new RedisStandaloneIndexSnapshot.Phase();
        phase.addBoundedOwners(keys, 8);
        assertEquals(512, phase.size());
        CommandArguments template = phase.commands.getFirst();
        assertTrue(phase.commands.stream().allMatch(command -> command == template));
        byte[] encoded = serialize(phase);
        try (var input = new RedisInputStream(new ByteArrayInputStream(encoded))) {
            for (String key : keys) {
                Object decoded = Protocol.read(input);
                assertInstanceOf(List.class, decoded);
                var command = (List<?>) decoded;
                assertEquals(4, command.size());
                assertArrayEquals(bytes("GETRANGE"), (byte[]) command.get(0));
                assertArrayEquals(bytes(key), (byte[]) command.get(1));
                assertArrayEquals(bytes("0"), (byte[]) command.get(2));
                assertArrayEquals(bytes("8"), (byte[]) command.get(3));
            }
            assertEquals(0, input.available());
        }
    }

    @Test void appendingProofIntoTransactionPreservesOwnerOffsetsAndSerializedKeyOrder() throws Exception {
        var proof = new RedisStandaloneIndexSnapshot.Phase();
        proof.add(new CommandArguments(Command.EXISTS).key("entity"));
        proof.add(new CommandArguments(Command.EXISTS).key("tomb"));
        proof.addBoundedOwners(List.of("x", "\u4e00".repeat(300), "z"), 4);
        var transaction = new RedisStandaloneIndexSnapshot.Phase();
        transaction.add(new CommandArguments(Command.MULTI));
        transaction.append(proof);
        transaction.add(new CommandArguments(Command.EXEC));
        try (var input = new RedisInputStream(new ByteArrayInputStream(serialize(transaction)))) {
            var names = new ArrayList<String>(); var owners = new ArrayList<String>();
            for (int i = 0; i < transaction.size(); i++) {
                var command = (List<?>) Protocol.read(input);
                String name = new String((byte[]) command.get(0), StandardCharsets.UTF_8); names.add(name);
                if (name.equals("GETRANGE")) owners.add(new String((byte[]) command.get(1), StandardCharsets.UTF_8));
            }
            assertEquals(List.of("MULTI", "EXISTS", "EXISTS", "GETRANGE", "GETRANGE", "GETRANGE", "EXEC"), names);
            assertEquals(List.of("x", "\u4e00".repeat(300), "z"), owners);
        }
    }

    @Test void independentRequestTemplatesDoNotLeakKeysOrInclusiveBoundsWhenInterleaved() throws Exception {
        var first = new RedisStandaloneIndexSnapshot.Phase();
        first.addBoundedOwners(List.of("a0", "a1"), 2);
        var second = new RedisStandaloneIndexSnapshot.Phase();
        second.addBoundedOwners(List.of("b0", "b1"), 7);
        var bytes = new ByteArrayOutputStream();
        try (var output = new RedisOutputStream(bytes, 7)) {
            for (int i = 0; i < 2; i++) {
                Protocol.sendCommand(output, first.commandAt(i));
                Protocol.sendCommand(output, second.commandAt(i));
            }
            output.flush();
        }
        try (var input = new RedisInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            for (String expected : List.of("a0", "b0", "a1", "b1")) {
                var command = (List<?>) Protocol.read(input);
                assertArrayEquals(bytes(expected), (byte[]) command.get(1));
                assertArrayEquals(bytes(expected.startsWith("a") ? "2" : "7"), (byte[]) command.get(3));
            }
        }
    }

    @Test void fullCountProofStillHas516CommandsAnd512DistinctAlignedOwnerReplies() {
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            var keys = IntStream.range(0, 512).mapToObj(i -> "owner:" + i).toList();
            var values = new ArrayList<Object>(); values.add(512L); values.add(0L);
            for (int i = 0; i < 512; i++) values.add(bytes("a"));
            var replies = new ArrayList<Object>(); replies.add(bytes("OK"));
            for (int i = 0; i < 514; i++) replies.add(bytes("QUEUED"));
            replies.add(values); pool.connection.reply(replies);
            assertEquals(512, RedisStandaloneIndexSnapshot.aggregate(pool, keys, keys, keys, keys, "a", null, true)
                    .orElseThrow().count());
            var commands = pool.connection.phases.getFirst();
            assertEquals(516, commands.size());
            for (int i = 0; i < 512; i++) assertEquals(List.of("GETRANGE", keys.get(i), "0", "1"), commands.get(i + 3));
            assertEquals(1, pool.healthyReturns); assertEquals(0, pool.brokenReturns);
        }
    }

    @Test void templateConstructionStillEnforcesCommandAndEncodedByteBudgetsBeforeSend() {
        var tooMany = new RedisStandaloneIndexSnapshot.Phase();
        var commandLimit = assertThrows(RuntimeException.class, () ->
                tooMany.addBoundedOwners(Collections.nCopies(517, "owner"), 1));
        assertEquals(516, tooMany.size());
        var tooLarge = new RedisStandaloneIndexSnapshot.Phase();
        var byteLimit = assertThrows(RuntimeException.class, () ->
                tooLarge.addBoundedOwners(Collections.nCopies(400, "\u4e00".repeat(16000)), 1));
        assertTrue(tooLarge.size() < 400);
        var binaryTooMany = new RedisStandaloneIndexSnapshot.Phase();
        var ownerKeys = new byte[517][];
        Arrays.fill(ownerKeys, bytes("owner"));
        assertEquals(commandLimit.getClass(),
                assertThrows(RuntimeException.class, () -> binaryTooMany.addBoundedOwners(ownerKeys, 1)).getClass());
        assertEquals(tooMany.size(), binaryTooMany.size());
        var binaryTooLarge = new RedisStandaloneIndexSnapshot.Phase();
        var largeKeys = new byte[400][];
        Arrays.fill(largeKeys, bytes("\u4e00".repeat(16000)));
        assertEquals(byteLimit.getClass(),
                assertThrows(RuntimeException.class, () -> binaryTooLarge.addBoundedOwners(largeKeys, 1)).getClass());
        assertEquals(tooLarge.size(), binaryTooLarge.size());
    }

    @Test void nativePrimaryMultiwindowCountKeepsFullAndTailTransactionWireOrder() {
        int rows = 1025;
        String tenant = "\u015firket";
        var entities = IntStream.range(0, rows).mapToObj(i -> "entity:" + i).toList();
        var tombstones = IntStream.range(0, rows).mapToObj(i -> "tomb:" + i).toList();
        var owners = IntStream.range(0, rows).mapToObj(i -> "owner:\u4e00:" + i).toList();
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            pool.connection.reply(countReply(512, tenant));
            pool.connection.reply(countReply(512, tenant));
            pool.connection.reply(countReply(1, tenant));
            assertEquals(rows, RedisStandaloneIndexSnapshot.indexCounts(pool, rows, start -> {
                int end = Math.min(rows, start + 512);
                return new RedisClientOperations.IndexReadKeys(entities.subList(start, end), tombstones.subList(start, end),
                        Collections.nCopies(end - start, null), owners.subList(start, end));
            }, tenant).orElseThrow());
            assertEquals(List.of(516, 516, 5), pool.connection.requestedReplies);
            var wire = pool.connection.phases.getFirst();
            assertEquals(rows + 12, wire.size());
            int offset = 0;
            for (int start = 0; start < rows; start += 512) {
                int end = Math.min(rows, start + 512);
                assertEquals(List.of("MULTI"), wire.get(offset++));
                var expectedEntities = new ArrayList<String>(); expectedEntities.add("EXISTS");
                expectedEntities.addAll(entities.subList(start, end));
                assertEquals(expectedEntities, wire.get(offset++));
                var expectedTombstones = new ArrayList<String>(); expectedTombstones.add("EXISTS");
                expectedTombstones.addAll(tombstones.subList(start, end));
                assertEquals(expectedTombstones, wire.get(offset++));
                for (int i = start; i < end; i++)
                    assertEquals(List.of("GETRANGE", owners.get(i), "0", Integer.toString(bytes(tenant).length)), wire.get(offset++));
                assertEquals(List.of("EXEC"), wire.get(offset++));
            }
            assertEquals(wire.size(), offset);
            assertTrue(pool.connection.phases.subList(1, 3).stream().allMatch(List::isEmpty));
            assertEquals(1, pool.borrows); assertEquals(1, pool.healthyReturns); assertEquals(0, pool.brokenReturns);
            assertFalse(pool.connection.names().contains("EVAL"));
        }
    }

    @Test void bulkCountRejectsInvalidBatchByteBudgetBeforeBorrowingOrSending() {
        var keys = Collections.nCopies(513, "\u4e00".repeat(16000));
        try (var pool = new RedisStandaloneIndexSnapshotTest.FakePool()) {
            var error = assertThrows(IllegalArgumentException.class, () ->
                    RedisStandaloneIndexSnapshot.indexCounts(pool, keys.size(), start -> {
                        var batch = keys.subList(start, Math.min(keys.size(), start + 512));
                        return new RedisClientOperations.IndexReadKeys(batch, batch, batch, batch);
                    }, "a"));
            assertEquals("Index batch key byte budget exceeded", error.getMessage());
            assertEquals(0, pool.borrows); assertEquals(0, pool.connection.sends);
        }
    }

    @Test void stockCountExistsKeepsLegacyWireBytesAndBudgetForMalformedUnicode() throws Exception {
        var parts = List.of("plain", "\u015firket:\u4e00:\uD83D\uDE80", "\uD800:x", "\uDC00:y");
        var entities = IntStream.range(0, 512).mapToObj(i -> "entity:" + parts.get(i % parts.size()) + ":" + i).toList();
        var tombstones = IntStream.range(0, 512).mapToObj(i -> "tomb:" + parts.get(i % parts.size()) + ":" + i).toList();
        var owners = IntStream.range(0, 512).mapToObj(i -> "owner:" + i).toList();
        var stock = countTransaction(entities, tombstones, owners, "\u015firket", true);
        var legacy = countTransaction(entities, tombstones, owners, "\u015firket", false);
        assertArrayEquals(serialize(legacy), serialize(stock));
        var budget = RedisStandaloneIndexSnapshot.Phase.class.getDeclaredField("bytes");
        budget.setAccessible(true);
        assertEquals(budget.getLong(legacy), budget.getLong(stock));
        for (int index : List.of(1, 2)) {
            var arguments = stock.commandAt(index).iterator(); arguments.next();
            while (arguments.hasNext()) {
                var argument = arguments.next();
                assertFalse(argument instanceof RawableFactory.Raw);
                assertSame(argument.getRaw(), argument.getRaw());
            }
        }
    }

    @Test void stockCountExistsBytesRemainRequestLocalAcrossTransactions() throws Exception {
        var first = countTransaction(List.of("entity:\u015firket"), List.of("tomb:\u4e00"), List.of("owner"), "a", true);
        byte[] original = serialize(first);
        var second = countTransaction(List.of("entity:\u015firket"), List.of("tomb:\u4e00"), List.of("owner"), "a", true);
        for (int index : List.of(1, 2)) {
            var firstArguments = first.commandAt(index).iterator(); firstArguments.next();
            var secondArguments = second.commandAt(index).iterator(); secondArguments.next();
            var firstKey = firstArguments.next().getRaw();
            var secondKey = secondArguments.next().getRaw();
            assertArrayEquals(firstKey, secondKey);
            assertNotSame(firstKey, secondKey);
            Arrays.fill(secondKey, (byte) 'x');
        }
        assertArrayEquals(original, serialize(first));
    }

    @Test void bulkCustomConnectionNativePrimaryRetainsIndependentOwnerArgumentsAcrossWindows() {
        for (boolean binary : List.of(false, true)) {
            int rows = 1025;
            var keys = IntStream.range(0, rows).mapToObj(i -> "owner:\u015firket:" + i).toList();
            var strategy = new RedisKeyStrategy();
            var expectedOwners = binary
                    ? keys.stream().map(id -> strategy.versionKey("ns", id) + ":tenant").toList() : keys;
            var retained = new ArrayList<CommandArguments>();
            Connection connection = new Connection() {
                private int reads;
                @Override public boolean isConnected() { return true; }
                @Override public void sendCommand(CommandArguments args) { retained.add(args); }
                @Override public List<Object> getMany(int count) {
                    assertEquals(rows + 12, retained.size(), "All windows must be sent before reading");
                    var distinct = Collections.newSetFromMap(new IdentityHashMap<CommandArguments, Boolean>());
                    var observedOwners = new ArrayList<String>();
                    for (CommandArguments command : retained) {
                        if (command.getCommand() == Command.EXISTS) {
                            var arguments = command.iterator(); arguments.next();
                            while (arguments.hasNext())
                                assertInstanceOf(RawableFactory.RawString.class, arguments.next());
                        }
                        if (command.getCommand() != Command.GETRANGE) continue;
                        assertTrue(distinct.add(command), "Custom connections require independent owner commands");
                        var args = command.iterator(); args.next();
                        observedOwners.add(new String(args.next().getRaw(), StandardCharsets.UTF_8));
                        assertArrayEquals(bytes("0"), args.next().getRaw());
                        assertArrayEquals(bytes("1"), args.next().getRaw());
                    }
                    assertEquals(expectedOwners, observedOwners);
                    int size = Math.min(512, rows - reads++ * 512);
                    var reply = countReply(size, "a"); assertEquals(count, reply.size()); return reply;
                }
            };
            int[] borrows = {0}, discarded = {0};
            var config = new GenericObjectPoolConfig<Connection>(); config.setJmxEnabled(false);
            try (var pool = new Pool<Connection>(new BasePooledObjectFactory<Connection>() {
                public Connection create() { throw new AssertionError("No socket expected"); }
                public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
            }, config) {
                @Override public Connection borrowObject(Duration wait) { borrows[0]++; return connection; }
                @Override public void returnResource(Connection value) { assertSame(connection, value); }
                @Override public void returnBrokenResource(Connection value) { assertSame(connection, value); discarded[0]++; }
            }) {
                var count = binary ? RedisStandaloneIndexSnapshot.indexCounts(pool,
                        new RedisBinaryCountKeys(strategy, "ns", keys, "a"), "a")
                        : RedisStandaloneIndexSnapshot.indexCounts(pool, rows, start -> {
                            var batch = keys.subList(start, Math.min(rows, start + 512));
                            return new RedisClientOperations.IndexReadKeys(batch, batch, batch, batch);
                        }, "a");
                assertEquals(rows, count.orElseThrow());
                assertEquals(1, borrows[0]);
                assertEquals(0, discarded[0]);
                assertTrue(retained.stream().noneMatch(command -> command.getCommand() == Command.EVAL));
            }
        }
    }

    @Test void deferredCustomConnectionReceivesIndependentArgumentsNotTheStockOnlyTemplate() {
        var keys = IntStream.range(0, 512).mapToObj(i -> "owner:" + i).toList();
        var retained = new ArrayList<CommandArguments>();
        Connection connection = new Connection() {
            @Override public boolean isConnected() { return true; }
            @Override public void sendCommand(CommandArguments args) { retained.add(args); }
            @Override public List<Object> getMany(int count) {
                var actualOwners = new ArrayList<String>();
                for (CommandArguments command : retained) {
                    if (command.getCommand() != Command.GETRANGE) continue;
                    var iterator = command.iterator(); iterator.next();
                    actualOwners.add(new String(iterator.next().getRaw(), StandardCharsets.UTF_8));
                }
                assertEquals(keys, actualOwners);
                var values = new ArrayList<Object>(); values.add(512L); values.add(0L);
                for (int i = 0; i < 512; i++) values.add(bytes("a"));
                var replies = new ArrayList<Object>(); replies.add(bytes("OK"));
                for (int i = 0; i < 514; i++) replies.add(bytes("QUEUED"));
                replies.add(values); assertEquals(count, replies.size()); return replies;
            }
        };
        var config = new GenericObjectPoolConfig<Connection>(); config.setJmxEnabled(false);
        try (var pool = new Pool<Connection>(new BasePooledObjectFactory<Connection>() {
            public Connection create() { throw new AssertionError("No socket expected"); }
            public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
        }, config) {
            @Override public Connection borrowObject(Duration wait) { return connection; }
            @Override public void returnResource(Connection value) { assertSame(connection, value); }
            @Override public void returnBrokenResource(Connection value) { fail("Healthy custom connection was discarded"); }
        }) {
            assertEquals(512, RedisStandaloneIndexSnapshot.aggregate(pool, keys, keys, keys, keys, "a", null, true)
                    .orElseThrow().count());
        }
    }

    @Test void indexEvalSharesIdenticalScriptBytesButNotCommandsOrKeyArgumentsWithinPhase() throws Exception {
        String script = "return ARGV[1]";
        var phase = new RedisStandaloneIndexSnapshot.Phase();
        var legacy = new RedisStandaloneIndexSnapshot.Phase();
        for (int i = 0; i < 2; i++) {
            phase.addIndexEval(new String(script), List.of("key"), List.of("value"));
            legacy.add(RedisStandaloneIndexSnapshot.evalCommand(script, List.of("key"), List.of("value")));
        }
        assertNotSame(phase.commandAt(0), phase.commandAt(1));
        assertSame(argumentBytes(phase, 0, 1), argumentBytes(phase, 1, 1));
        assertNotSame(argumentBytes(phase, 0, 3), argumentBytes(phase, 1, 3));
        assertNotSame(argumentBytes(phase, 0, 4), argumentBytes(phase, 1, 4));
        assertArrayEquals(serialize(legacy), serialize(phase));
    }

    @Test void indexEvalScriptBytesDoNotLeakAcrossRequestPhases() throws Exception {
        var first = new RedisStandaloneIndexSnapshot.Phase();
        var second = new RedisStandaloneIndexSnapshot.Phase();
        first.addIndexEval("return 1", List.of(), List.of());
        second.addIndexEval("return 1", List.of(), List.of());
        byte[] original = serialize(first);
        assertArrayEquals(argumentBytes(first, 0, 1), argumentBytes(second, 0, 1));
        assertNotSame(argumentBytes(first, 0, 1), argumentBytes(second, 0, 1));
        Arrays.fill(argumentBytes(second, 0, 1), (byte) 'x');
        assertArrayEquals(original, serialize(first));
    }

    @Test void indexEvalDifferentScriptCannotReuseStaleBytes() throws Exception {
        var phase = new RedisStandaloneIndexSnapshot.Phase();
        phase.addIndexEval("return 1", List.of(), List.of());
        phase.addIndexEval("return 2", List.of(), List.of());
        phase.addIndexEval(new String("return 1"), List.of(), List.of());
        assertArrayEquals(bytes("return 1"), argumentBytes(phase, 0, 1));
        assertArrayEquals(bytes("return 2"), argumentBytes(phase, 1, 1));
        assertNotSame(argumentBytes(phase, 0, 1), argumentBytes(phase, 1, 1));
        assertSame(argumentBytes(phase, 0, 1), argumentBytes(phase, 2, 1));
    }

    @Test void indexEvalSharedScriptChargesFullBytesForEveryTransmissionAndRejectsAtLegacyBoundary() throws Exception {
        String script = "x".repeat(1024 * 1024);
        long perCommand = script.length() + 53L;
        int admitted = (int) (RedisStandaloneIndexSnapshot.MAX_BYTES / perCommand);
        var phase = new RedisStandaloneIndexSnapshot.Phase();
        var legacy = new RedisStandaloneIndexSnapshot.Phase();
        for (int i = 0; i < admitted; i++) {
            phase.addIndexEval(script, List.of(), List.of());
            legacy.add(RedisStandaloneIndexSnapshot.evalCommand(script, List.of(), List.of()));
        }
        assertEquals(admitted * perCommand, phaseBytes(phase));
        assertEquals(phaseBytes(legacy), phaseBytes(phase));
        var sharedFailure = assertThrows(RuntimeException.class, () -> phase.addIndexEval(script, List.of(), List.of()));
        var legacyFailure = assertThrows(RuntimeException.class,
                () -> legacy.add(RedisStandaloneIndexSnapshot.evalCommand(script, List.of(), List.of())));
        assertEquals(legacyFailure.getClass(), sharedFailure.getClass());
        assertEquals(admitted, phase.size());
        assertEquals(legacy.size(), phase.size());
        assertEquals(phaseBytes(legacy), phaseBytes(phase));
    }

    @Test void indexEvalSharingPreservesLegacyWireAndBudgetForUtf8AndMalformedSurrogates() throws Exception {
        String script = "return '\u015firket:\u4e00:\uD83D\uDE80' -- \uD800:x\uDC00";
        var keys = List.of("key:\uD800:x", "key:\u015firket:\u4e00");
        var arguments = List.of("arg:\uDC00:y", "arg:\uD83D\uDE80");
        var phase = new RedisStandaloneIndexSnapshot.Phase();
        var legacy = new RedisStandaloneIndexSnapshot.Phase();
        for (int i = 0; i < 2; i++) {
            phase.addIndexEval(script, keys, arguments);
            legacy.add(RedisStandaloneIndexSnapshot.evalCommand(script, keys, arguments));
        }
        assertArrayEquals(serialize(legacy), serialize(phase));
        assertArrayEquals(bytes(script), argumentBytes(phase, 0, 1));
        assertEquals(phaseBytes(legacy), phaseBytes(phase));
    }

    @Test void customIndexEvalScriptsRetainIndependentBuffersWithoutChangingWireOrBudget() throws Exception {
        var phase = new RedisStandaloneIndexSnapshot.Phase();
        for (int i = 0; i < 2; i++) phase.addIndexEval("return ARGV[1]", List.of("key:" + i), List.of("value"));
        byte[] wire = serialize(phase);
        long budget = phaseBytes(phase);
        byte[] key = argumentBytes(phase, 0, 3);
        phase.independentIndexEvalScripts();
        assertNotSame(phase.commandAt(0), phase.commandAt(1));
        assertNotSame(argumentBytes(phase, 0, 1), argumentBytes(phase, 1, 1));
        assertSame(key, argumentBytes(phase, 0, 3));
        assertArrayEquals(wire, serialize(phase));
        assertEquals(budget, phaseBytes(phase));
        Arrays.fill(argumentBytes(phase, 0, 1), (byte) 'x');
        assertArrayEquals(bytes("return ARGV[1]"), argumentBytes(phase, 1, 1));
    }

    @Test void countAndFacetEvalPhasesKeepDeferredCustomConnectionScriptsIndependent() {
        int rows = 513;
        var keys = IntStream.range(0, rows).mapToObj(i -> "key:" + i).toList();
        for (boolean facet : List.of(false, true)) {
            var retained = new ArrayList<CommandArguments>();
            Connection connection = new Connection() {
                @Override public boolean isConnected() { return true; }
                @Override public void sendCommand(CommandArguments command) { retained.add(command); }
                @Override public List<Object> getMany(int count) {
                    assertEquals(2, count); assertEquals(2, retained.size());
                    assertNotSame(retained.get(0), retained.get(1));
                    var first = retained.get(0).iterator(); first.next();
                    var second = retained.get(1).iterator(); second.next();
                    byte[] firstScript = first.next().getRaw(), secondScript = second.next().getRaw();
                    assertArrayEquals(firstScript, secondScript);
                    assertNotSame(firstScript, secondScript);
                    var replies = new ArrayList<Object>();
                    for (long size : List.of(512L, 1L)) {
                        if (facet) replies.add(List.of(List.of(List.of(bytes("group"), size)), List.of()));
                        else replies.add(List.of(size, List.of(), List.of()));
                    }
                    return replies;
                }
            };
            var config = new GenericObjectPoolConfig<Connection>(); config.setJmxEnabled(false);
            try (var pool = new Pool<Connection>(new BasePooledObjectFactory<Connection>() {
                public Connection create() { throw new AssertionError("No socket expected"); }
                public PooledObject<Connection> wrap(Connection value) { return new DefaultPooledObject<>(value); }
            }, config) {
                @Override public Connection borrowObject(Duration wait) { return connection; }
                @Override public void returnResource(Connection value) { assertSame(connection, value); }
                @Override public void returnBrokenResource(Connection value) { fail("Healthy custom connection was discarded"); }
            }) {
                java.util.function.IntFunction<RedisClientOperations.IndexReadKeys> windows = start -> {
                    var batch = keys.subList(start, Math.min(rows, start + 512));
                    return new RedisClientOperations.IndexReadKeys(batch, batch, batch, batch);
                };
                if (facet) {
                    var result = RedisStandaloneIndexSnapshot.facetCountEvals(pool, rows, windows, "a", "group").orElseThrow();
                    assertEquals(List.of(512L, 1L), result.stream().map(batch -> batch.counts().get("group")).toList());
                } else assertEquals(rows, RedisStandaloneIndexSnapshot.indexCountEvals(pool, rows, windows, "a").orElseThrow());
            }
        }
    }

    private static byte[] argumentBytes(RedisStandaloneIndexSnapshot.Phase phase, int command, int argument) {
        var arguments = phase.commandAt(command).iterator();
        for (int i = 0; i < argument; i++) arguments.next();
        return arguments.next().getRaw();
    }

    private static long phaseBytes(RedisStandaloneIndexSnapshot.Phase phase) throws Exception {
        var budget = RedisStandaloneIndexSnapshot.Phase.class.getDeclaredField("bytes");
        budget.setAccessible(true);
        return budget.getLong(phase);
    }

    private static RedisStandaloneIndexSnapshot.Phase countTransaction(List<String> entities, List<String> tombstones,
            List<String> owners, String tenant, boolean reusable) throws Exception {
        var batchType = Class.forName(RedisStandaloneIndexSnapshot.class.getName() + "$Batch");
        var create = batchType.getDeclaredMethod("create", List.class, List.class, List.class, List.class, String.class, List.class);
        create.setAccessible(true);
        var batch = create.invoke(null, entities, tombstones, Collections.nCopies(entities.size(), null), owners, tenant, List.of());
        var transaction = RedisStandaloneIndexSnapshot.class.getDeclaredMethod("countTransaction", batchType, boolean.class);
        transaction.setAccessible(true);
        return (RedisStandaloneIndexSnapshot.Phase) transaction.invoke(null, batch, reusable);
    }

    private static List<Object> countReply(int rows, String tenant) {
        var values = new ArrayList<Object>(); values.add((long) rows); values.add(0L);
        for (int i = 0; i < rows; i++) values.add(bytes(tenant));
        var replies = new ArrayList<Object>(); replies.add(bytes("OK"));
        for (int i = 0; i < rows + 2; i++) replies.add(bytes("QUEUED"));
        replies.add(values); return replies;
    }

    private static byte[] serialize(RedisStandaloneIndexSnapshot.Phase phase) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new RedisOutputStream(bytes, 17)) {
            for (int i = 0; i < phase.size(); i++) Protocol.sendCommand(output, phase.commandAt(i));
            output.flush();
        }
        return bytes.toByteArray();
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}

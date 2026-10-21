package com.reactor.cachedb.examples.snapshotjson;

import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.spring.boot.snapshot.InMemorySnapshotRepository;
import com.reactor.cachedb.spring.boot.snapshot.SnapshotStatus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;

/** Runnable without Redis, JDBC, Spring context or Jackson JARs. Each mode includes first use. */
public final class JacksonFreeSnapshotExample {
    private static volatile Object result;
    private static long[] stages;

    private static void stage(int index) {
        if (stages != null) stages[index] = System.nanoTime();
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "snapshot" : args[0];
        long start = System.nanoTime();
        if (args.length > 2 && args[2].equals("stages")) {
            stages = new long[9];
            stages[0] = start;
        }
        run(mode);
        long elapsed = System.nanoTime() - start;
        System.out.println("mode=" + mode + " nanos=" + elapsed);
        if (stages != null) {
            String[] names = {
                "",
                "repository",
                "typed-view",
                "unavailable",
                "put",
                "decode",
                "states",
                "multi-point",
                "paging"
            };
            for (int i = 1; i < stages.length; i++)
                System.out.println("stage=" + names[i] + " nanos=" + (stages[i] - stages[i - 1]));
            stages = null;
        }
        if (args.length > 1) {
            int repetitions = Integer.parseInt(args[1]);
            if (repetitions < 1 || repetitions > 10_000)
                throw new IllegalArgumentException("Invalid repetitions");
            for (int i = 0; i < repetitions; i++) {
                start = System.nanoTime();
                run(mode);
                System.out.println(
                        "mode=" + mode + " repeat=" + i + " nanos=" + (System.nanoTime() - start));
            }
        }
    }

    private static void run(String mode) throws Exception {
        switch (mode) {
            case "decode" -> {
                var values =
                        CatalogSummaryJsonCodec.INSTANCE.decodeArray("[{\"title\":\"catalog\"}]");
                require(values.size() == 1 && values.get(0).title().equals("catalog"));
                result = values;
            }
            case "roundtrip" -> {
                var bytes = new ByteArrayOutputStream();
                CatalogSummaryJsonCodec.INSTANCE.encodeArray(
                        List.of(new CatalogSummary("catalog")), bytes);
                var values =
                        CatalogSummaryJsonCodec.INSTANCE.decodeArray(
                                bytes.toString(StandardCharsets.UTF_8));
                require(values.size() == 1 && values.get(0).title().equals("catalog"));
                result = values;
            }
            case "snapshot" -> {
                var clock = new MutableClock();
                var raw = new InMemorySnapshotRepository(clock, Duration.ofMinutes(1));
                stage(1);
                var typed = raw.typedRepository(CatalogSummaryJsonCodec.INSTANCE);
                stage(2);
                require(typed.lookup("a").status() == SnapshotStatus.SNAPSHOT_UNAVAILABLE);
                stage(3);
                raw.putSnapshot("a", "[{\"title\":\"catalog\"}]", "p", 1);
                raw.putSnapshot("b", "[]", "p", 2);
                stage(4);
                require(typed.lookup("a").values().get(0).title().equals("catalog"));
                stage(5);
                require(typed.lookup("missing").status() == SnapshotStatus.ABSENT_IN_GENERATION);
                require(typed.lookup("b").values().isEmpty() && typed.lookup("b").isPresent());
                clock.now = clock.now.plusSeconds(60);
                require(typed.lookup("a").isStaleWarning());
                stage(6);
                require(typed.lookupAll(List.of("a", "missing")).size() == 2);
                stage(7);
                var first = typed.findTypedPage("p", WindowRequest.first(1));
                require(
                        typed.findTypedPage("p", first.nextRequest(1).orElseThrow())
                                .items()
                                .get(0)
                                .id()
                                .equals("b"));
                stage(8);
                raw.expire();
                require(!typed.lookup("a").isSnapshotAvailable());
                result = typed.lookup("a");
            }
            default -> throw new IllegalArgumentException("Unknown scenario: " + mode);
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Snapshot contract mismatch");
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.ofEpochMilli(1_000_000);

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }
}

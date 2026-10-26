package com.reactor.cachedb.spring.boot.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotSpoolDigestTest {
    @TempDir Path directory;
    @Test void digestMatchesLegacyUtf8AndReuseSkipsPayloadLoading() throws Exception {
        var roots = new SnapshotSource<String>("roots", "SELECT id FROM roots", row -> row.get("id").toString());
        var plan = SnapshotPlan.streaming("catalog", roots, value -> value, String.class,
                value -> List.of("\u0130stanbul \u00e7\u011f\u00fc \ud83d\ude80", "a\u0000b")).withContentReuse();
        try (var spool = new SnapshotSpool(directory)) {
            spool.prepare(plan, new SnapshotRows(Map.of(roots, List.of("root-1"))), new ObjectMapper(), () -> {});
            var entry = spool.entries().get(0);
            String json = spool.read(entry, () -> {});
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((entry.id()+"\u0000"+json).getBytes(StandardCharsets.UTF_8)));
            assertThat(entry.contentDigest()).isEqualTo(expected);
            AtomicInteger reads = new AtomicInteger();
            var reused = SnapshotPublication.batch("test:{catalog}", "new", "test:{catalog}:lock", "owner",
                    60000, true, spool.entries(), row -> { reads.incrementAndGet(); return json; }, Set.of(expected));
            assertThat(reads).hasValue(0);
            assertThat(reused.arguments().get(5)).isEmpty();
            var fallback = SnapshotPublication.batch("test:{catalog}", "new", "test:{catalog}:lock", "owner",
                    60000, true, spool.entries(), row -> { reads.incrementAndGet(); return json; });
            assertThat(reads).hasValue(1);
            assertThat(fallback.arguments().get(5)).isEqualTo(json);
        }
        assertThat(directory.toFile().list()).isEmpty();
    }
    @Test void legacyEntryConstructorRemainsAvailable() {
        assertThat(new SnapshotSpool.Entry("root", 0, 2, null, 0).contentDigest()).isNull();
    }

    @Test void compressedSpoolKeepsLogicalBudgetsDigestsAndPublishedJsonUnchanged() throws Exception {
        var roots = new SnapshotSource<String>("roots", "SELECT id FROM roots", row -> row.get("id").toString());
        var plan = SnapshotPlan.streaming("catalog", roots, value -> value, String.class,
                value -> List.of("İstanbul ürün açıklaması ".repeat(500), value))
                .withSpoolCompression(1024).withContentReuse();
        assertThat(plan.spoolCompressionThresholdBytes()).isEqualTo(1024);
        try (var spool = new SnapshotSpool(directory)) {
            spool.prepare(plan, new SnapshotRows(Map.of(roots, List.of("root-1", "root-2"))), new ObjectMapper(), () -> {});
            long logicalBytes = 0;
            for (var entry : spool.entries()) {
                String json = spool.read(entry, () -> {});
                assertThat(json).startsWith("[").contains(entry.id());
                assertThat(entry.bytes()).isEqualTo(json.getBytes(StandardCharsets.UTF_8).length);
                assertThat(entry.storedBytes()).isLessThan(entry.bytes());
                logicalBytes += entry.bytes();
                String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((entry.id() + "\u0000" + json).getBytes(StandardCharsets.UTF_8)));
                assertThat(entry.contentDigest()).isEqualTo(expected);
            }
            assertThat(spool.bytes()).isEqualTo(logicalBytes);
        }
        assertThat(directory.toFile().list()).isEmpty();
    }
}

package com.reactor.cachedb.core.api;

import static org.junit.jupiter.api.Assertions.*;

import com.reactor.cachedb.core.model.WriteDependency;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

class CacheWriteBatchReceiptTest {
    record Shape(
            String batchId,
            String streamEntryId,
            List<Long> versions,
            List<WriteDependency> dependencies) {}

    @Test
    void retainsRecordEqualityHashAndComponentOrder() {
        var random = new Random(14_021);
        for (int i = 0; i < 300; i++) {
            String id = i % 3 == 0 ? null : "batch-" + random.nextInt();
            String stream = i % 2 == 0 ? null : "stream-" + random.nextInt();
            var versions = List.of(random.nextLong(), random.nextLong());
            var dependencies = List.of(new WriteDependency("tickets", "1", i + 1));
            var receipt = new CacheWriteBatchReceipt(id, stream, versions, dependencies);
            var same = new CacheWriteBatchReceipt(id, stream, versions, dependencies);
            assertEquals(receipt, receipt);
            assertEquals(receipt, same);
            assertEquals(same, receipt);
            assertEquals(
                    new Shape(id, stream, versions, dependencies).hashCode(), receipt.hashCode());
            assertNotEquals(receipt, null);
            assertNotEquals(receipt, new Shape(id, stream, versions, dependencies));
            assertNotEquals(
                    receipt, new CacheWriteBatchReceipt("other", stream, versions, dependencies));
            assertNotEquals(
                    receipt, new CacheWriteBatchReceipt(id, "other", versions, dependencies));
            assertNotEquals(
                    receipt, new CacheWriteBatchReceipt(id, stream, List.of(1L), dependencies));
            assertNotEquals(receipt, new CacheWriteBatchReceipt(id, stream, versions));
        }
    }

    @Test
    void retainsDefensiveCopiesLegacyConstructorAndNullValidation() {
        var versions = new ArrayList<>(List.of(1L, 2L));
        var dependencies = new ArrayList<>(List.of(new WriteDependency("tickets", "1", 1)));
        var receipt = new CacheWriteBatchReceipt("id", "stream", versions, dependencies);
        versions.clear();
        dependencies.clear();
        assertEquals(List.of(1L, 2L), receipt.versions());
        assertEquals(1, receipt.dependencies().size());
        assertThrows(UnsupportedOperationException.class, () -> receipt.versions().clear());
        assertThrows(UnsupportedOperationException.class, () -> receipt.dependencies().clear());
        assertEquals(
                List.of(), new CacheWriteBatchReceipt("id", "stream", List.of(1L)).dependencies());
        assertThrows(
                NullPointerException.class, () -> new CacheWriteBatchReceipt("id", "stream", null));
        assertThrows(
                NullPointerException.class,
                () -> new CacheWriteBatchReceipt("id", "stream", List.of(1L), null));
    }
}

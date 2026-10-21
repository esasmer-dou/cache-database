package com.reactor.cachedb.spring.boot.snapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.reactor.cachedb.redis.RedisClientOperations;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

class SnapshotGenerationValidationTest {
    @Test
    void markerValidationKeepsExactlyTheLegacyCharacterContract() {
        var redis = mock(RedisClientOperations.class);
        var generation = new AtomicReference<String>();
        when(redis.eval(anyString(), anyList(), anyList()))
                .thenAnswer(
                        call -> List.of(generation.get(), "1000", "root", "FOUND", "1000", "[]"));
        var raw = new RedisSnapshotRepository(redis, "test:{snapshot}");
        Pattern legacy = Pattern.compile("[0-9a-f-]{36}");
        var random = new Random(140_023);
        for (int run = 0; run < 500; run++) {
            String alphabet = run % 2 == 0 ? "0123456789abcdef-" : "0123456789abcdef-GX\n\u0130";
            StringBuilder value = new StringBuilder();
            int length = run % 3 == 0 ? 35 : 36;
            for (int i = 0; i < length; i++)
                value.append(alphabet.charAt(random.nextInt(alphabet.length())));
            generation.set(value.toString());
            if (legacy.matcher(value).matches()) assertTrue(raw.lookupRaw("root").isPresent());
            else assertThrows(IllegalArgumentException.class, () -> raw.lookupRaw("root"));
        }
        // Do not accidentally strengthen the legacy format to UUID parsing in an optimization.
        generation.set("-".repeat(36));
        assertTrue(raw.lookupRaw("root").isPresent());
        generation.set("a".repeat(37));
        assertThrows(IllegalArgumentException.class, () -> raw.lookupRaw("root"));
    }
}

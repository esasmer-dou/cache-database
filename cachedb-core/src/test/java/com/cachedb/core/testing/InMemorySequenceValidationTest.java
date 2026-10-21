package com.reactor.cachedb.core.testing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Random;
import java.util.regex.Pattern;

class InMemorySequenceValidationTest {
    @Test
    void keepsLegacyNameValidationAllocationBoundsAndSequenceState() {
        var ids = new InMemoryIdGenerator(Clock.systemUTC());
        Pattern legacy = Pattern.compile("[A-Za-z0-9_.:-]+");
        var random = new Random(140_025);
        for (int sample = 0; sample < 1000; sample++) {
            StringBuilder name = new StringBuilder();
            int length = random.nextInt(20);
            String alphabet = sample % 2 == 0 ? "abcXYZ012_.:-" : "abcXYZ012_.:- /;\n\r\u0130";
            for (int i = 0; i < length; i++)
                name.append(alphabet.charAt(random.nextInt(alphabet.length())));
            if (legacy.matcher(name).matches())
                assertTrue(ids.nextSequence(name.toString(), 1) > 0);
            else
                assertThrows(
                        IllegalArgumentException.class, () -> ids.nextSequence(name.toString(), 1));
        }
        assertEquals(1, ids.nextSequence("orders", 64));
        assertThrows(IllegalArgumentException.class, () -> ids.nextSequence("orders", 0));
        assertThrows(IllegalArgumentException.class, () -> ids.nextSequence("orders", 10001));
        assertEquals(2, ids.nextSequence("orders", 10000));
        assertThrows(IllegalArgumentException.class, () -> ids.nextSequence(null, 1));
    }
}

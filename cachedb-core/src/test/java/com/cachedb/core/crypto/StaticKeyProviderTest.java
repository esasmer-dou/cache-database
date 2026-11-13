package com.reactor.cachedb.core.crypto;

import static com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.INVALID_CONFIGURATION;
import static com.reactor.cachedb.core.crypto.CryptoTestKeys.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StaticKeyProviderTest {
    @Test void constructorDefensivelyCopiesKeyBytesAndProviderCollections() {
        byte[] aes = aes(1);
        byte[] blind = hmac();
        KeyMaterial material = new KeyMaterial("k1", aes, blind);
        Arrays.fill(aes, (byte) 0);
        Arrays.fill(blind, (byte) 0);
        List<KeyMaterial> list = new ArrayList<>(List.of(material));
        Map<String, List<KeyMaterial>> rings = new HashMap<>(Map.of("default", list));
        Map<String, String> current = new HashMap<>(Map.of("default", "k1"));
        StaticKeyProvider copied = new StaticKeyProvider(rings, current);
        list.clear();
        rings.clear();
        current.clear();
        assertSame(material, copied.current("default"));
        assertSame(material, copied.byId("default", "k1").orElseThrow());
        assertTrue(copied.byId("other", "k1").isEmpty());
        assertTrue(copied.byId("default", "missing").isEmpty());
        JcaFieldEncryption writer = new JcaFieldEncryption(copied);
        JcaFieldEncryption reader = new JcaFieldEncryption(provider("k1", key(1)));
        String encrypted = writer.encrypt("default", "ns", "1", "secret", "value");
        assertEquals("value", reader.decrypt("default", "ns", "1", "secret", encrypted));
        assertEquals(reader.blindIndex("default", "ns", "secret", "value"), writer.blindIndex("default", "ns", "secret", "value"));
    }

    @Test void configAcceptsOnlyCanonicalPaddedBase64ForTwoDifferent256BitKeys() {
        String aes = Base64.getEncoder().encodeToString(aes(1));
        String hmac = Base64.getEncoder().encodeToString(hmac());
        KeyMaterial material = KeyMaterial.fromBase64("key-2026_10.1", aes, hmac);
        assertEquals("key-2026_10.1", material.keyId());
        for (String invalid : Arrays.asList(null, "", "secret-value", aes.substring(0, 43), aes + "\n",
                Base64.getEncoder().encodeToString(new byte[16]), "!".repeat(44))) {
            invalid(() -> KeyMaterial.fromBase64("k1", invalid, hmac));
            invalid(() -> KeyMaterial.fromBase64("k1", aes, invalid));
        }
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int last = alphabet.indexOf(aes.charAt(42));
        String alias = aes.substring(0, 42) + alphabet.charAt(last | 1) + '=';
        invalid(() -> KeyMaterial.fromBase64("k1", alias, hmac));
        invalid(() -> KeyMaterial.fromBase64("k1", aes, aes));
    }

    @Test void rejectsBadIdsWrongKeyLengthsNullAndReusedKeyPairsWithoutEchoingInputs() {
        for (String bad : Arrays.asList(null, "", "-bad", ".bad", "bad:id", "bad|id", "bad id", "\u00e9", "k".repeat(65))) {
            invalid(() -> new KeyMaterial(bad, aes(1), hmac()));
        }
        for (int size : List.of(0, 16, 24, 31, 33, 1024)) {
            invalid(() -> new KeyMaterial("k1", new byte[size], hmac()));
            invalid(() -> new KeyMaterial("k1", aes(1), new byte[size]));
        }
        invalid(() -> new KeyMaterial("k1", null, hmac()));
        invalid(() -> new KeyMaterial("k1", aes(1), null));
        invalid(() -> new KeyMaterial("k1", aes(1), aes(1)));
        assertEquals("KeyMaterial[redacted]", key(1).toString());
        assertEquals("StaticKeyProvider[redacted]", provider("k1", key(1)).toString());
    }

    @Test void rejectsMissingCurrentDuplicateIdsAliasesAndAccidentalBlindKeyRotation() {
        invalid(() -> provider("missing", key(1)));
        invalid(() -> provider("k1", key(1), new KeyMaterial("k1", aes(2), hmac())));
        invalid(() -> provider("k1", key(1), new KeyMaterial("alias", aes(1), hmac())));
        invalid(() -> provider("k1", key(1), new KeyMaterial("k2", aes(2), aes(99))));
        invalid(() -> new StaticKeyProvider(Map.of(), Map.of()));
        invalid(() -> new StaticKeyProvider(null, Map.of()));
        invalid(() -> new StaticKeyProvider(Map.of("default", List.of(key(1))), null));
        invalid(() -> new StaticKeyProvider(Map.of("default", List.of(key(1))), Map.of("other", "k1")));
        invalid(() -> new StaticKeyProvider(Map.of("default", List.of()), Map.of("default", "k1")));
    }

    @Test void encryptionKeysCannotAliasAcrossRingsOrBecomeBlindIndexKeys() {
        invalid(() -> new StaticKeyProvider(Map.of("one", List.of(key(1)), "two", List.of(key(1))),
                Map.of("one", "k1", "two", "k1")));
        KeyMaterial first = key(1);
        KeyMaterial crossPurpose = new KeyMaterial("k2", hmac(), aes(1));
        invalid(() -> new StaticKeyProvider(Map.of("one", List.of(first), "two", List.of(crossPurpose)),
                Map.of("one", "k1", "two", "k2")));
    }

    @Test void ringKeyAndTotalBudgetsAreExplicit() {
        assertEquals(16, StaticKeyProvider.MAX_RINGS);
        assertEquals(32, StaticKeyProvider.MAX_KEYS_PER_RING);
        assertEquals(256, StaticKeyProvider.MAX_KEYS);
        List<KeyMaterial> keys = new ArrayList<>();
        for (int i = 0; i < 33; i++) keys.add(key(i));
        invalid(() -> provider("k0", keys.toArray(KeyMaterial[]::new)));
        assertNotNull(provider("k0", keys.subList(0, 32).toArray(KeyMaterial[]::new)));
        Map<String, List<KeyMaterial>> rings = new HashMap<>();
        Map<String, String> active = new HashMap<>();
        for (int i = 0; i < 17; i++) {
            rings.put("r" + i, List.of(key(i)));
            active.put("r" + i, "k" + i);
        }
        invalid(() -> new StaticKeyProvider(rings, active));
        rings.remove("r16");
        active.remove("r16");
        assertNotNull(new StaticKeyProvider(rings, active));
        rings.clear();
        active.clear();
        int version = 0;
        for (int r = 0; r < 8; r++) {
            List<KeyMaterial> ring = new ArrayList<>();
            for (int i = 0; i < 32; i++) ring.add(key(version++));
            rings.put("r" + r, ring);
            active.put("r" + r, ring.get(0).keyId());
        }
        assertNotNull(new StaticKeyProvider(rings, active));
        rings.put("overflow", List.of(key(version)));
        active.put("overflow", "k" + version);
        invalid(() -> new StaticKeyProvider(rings, active));
    }

    private static void invalid(Runnable action) {
        CacheEncryptionException failure = assertThrows(CacheEncryptionException.class, action::run);
        assertEquals(INVALID_CONFIGURATION, failure.code());
        assertEquals("CacheDB encryption: INVALID_CONFIGURATION", failure.getMessage());
        assertNull(failure.getCause());
    }
}

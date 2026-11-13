package com.reactor.cachedb.core.crypto;

import static com.reactor.cachedb.core.crypto.CacheEncryptionException.Code.*;
import static com.reactor.cachedb.core.crypto.CryptoTestKeys.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class JcaFieldEncryptionTest {
    private final JcaFieldEncryption engine = new JcaFieldEncryption(provider("k1", key(1)));

    @Test void roundTripNullEmptyUnicodeAndEnvelopeLookingPlaintext() {
        assertNull(engine.encrypt("default", "ns", "1", "secret", null));
        assertNull(engine.decrypt("default", "ns", "1", "secret", null));
        assertNull(engine.blindIndex("default", "ns", "secret", null));
        for (String value : List.of("", "sensitive", "\u0130\u015f\u0131k \u00e7\u011f\u015f \ud83d\ude80", "~E1:k1:not-an-envelope", "a|b\n\u0000")) {
            String encoded = engine.encrypt("default", "ns", "1", "secret", value);
            assertTrue(encoded.startsWith("~E1:k1:"));
            assertFalse(encoded.contains("="));
            assertEquals(value, engine.decrypt("default", "ns", "1", "secret", encoded));
        }
    }

    @Test void wireIsInteroperableWithJcaAndUsesExactAadAndTagLength() throws Exception {
        String encoded = engine.encrypt("default", "ns", "42", "secret", "sensitive");
        byte[] packed = unpack(encoded);
        assertEquals(12 + "sensitive".length() + 16, packed.length);
        Cipher reference = Cipher.getInstance("AES/GCM/NoPadding");
        reference.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aes(1), "AES"), new GCMParameterSpec(128, packed, 0, 12));
        reference.updateAAD("ns|42|secret".getBytes(StandardCharsets.UTF_8));
        assertEquals("sensitive", new String(reference.doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8));
        byte[] nonce = new byte[12];
        Arrays.fill(nonce, (byte) 5);
        String external = referenceEnvelope(nonce, "from-jca".getBytes(StandardCharsets.UTF_8));
        assertEquals("from-jca", engine.decrypt("default", "ns", "42", "secret", external));
    }

    @Test void rejectsEveryWrongAadDimensionIncludingTenantNamespace() {
        String encoded = engine.encrypt("default", "tenant-a:orders", "42", "secret", "sensitive");
        code(AUTHENTICATION_FAILED, () -> engine.decrypt("default", "tenant-b:orders", "42", "secret", encoded));
        code(AUTHENTICATION_FAILED, () -> engine.decrypt("default", "tenant-a:orders", "43", "secret", encoded));
        code(AUTHENTICATION_FAILED, () -> engine.decrypt("default", "tenant-a:orders", "42", "other", encoded));
    }

    @Test void rejectsAmbiguousAndOversizedAadBeforeKeyLookup() {
        AtomicInteger lookups = new AtomicInteger();
        JcaFieldEncryption guarded = new JcaFieldEncryption(countingProvider(lookups));
        for (String bad : Arrays.asList(null, "", "a|b", "a\nb", "a\u0000b", "a\u007fb", "\ud800", "x".repeat(1025))) {
            code(INVALID_CONTEXT, () -> guarded.encrypt("default", bad, "42", "secret", "x"));
            code(INVALID_CONTEXT, () -> guarded.decrypt("default", "ns", bad, "secret", "x"));
            code(INVALID_CONTEXT, () -> guarded.blindIndex("default", bad, "secret", "x"));
        }
        code(INVALID_CONTEXT, () -> guarded.encrypt("default", "ns", "42", "c".repeat(129), "x"));
        code(INVALID_CONTEXT, () -> guarded.encrypt("default", "\u20ac".repeat(342), "42", "secret", "x"));
        code(INVALID_CONTEXT, () -> guarded.decrypt("default", "ns", "42", "a|b", null));
        assertEquals(0, lookups.get());
    }

    @Test void legacyPlaintextAndFutureVersionsFailClosedOnlyOnExplicitEncryptedReads() {
        for (String invalid : List.of("", "plain-secret", "~E2:k1:AAAA", "~E1", "~E1:k1", "~E1::AAAA")) {
            code(INVALID_ENVELOPE, () -> engine.decrypt("default", "ns", "1", "secret", invalid));
        }
        // Unencrypted fields must bypass the engine; do not classify fields by a value prefix.
        String legacy = "~E1:this-is-ordinary-text";
        assertEquals(legacy, engine.decrypt("default", "ns", "1", "secret",
                engine.encrypt("default", "ns", "1", "secret", legacy)));
    }

    @Test void rejectsNonCanonicalBase64AndBadHeadersBeforeProviderLookup() {
        String encoded = engine.encrypt("default", "ns", "1", "secret", "");
        String data = encoded.substring(encoded.lastIndexOf(':') + 1);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int last = alphabet.indexOf(data.charAt(data.length() - 1));
        String alias = data.substring(0, data.length() - 1) + alphabet.charAt(last | 1);
        AtomicInteger lookups = new AtomicInteger();
        JcaFieldEncryption guarded = new JcaFieldEncryption(countingProvider(lookups));
        for (String invalid : List.of(encoded + "=", encoded + "\n", "~E1:k1:" + alias,
                "~E1:k1:AAAAA", "~E1:k1:" + "A".repeat(36), "~E1:k1:" + data + ":extra",
                "~E1:bad|id:" + data, "~E1:-id:" + data, "~E1:" + "x".repeat(65) + ':' + data,
                "~E1:k1:" + "A".repeat(37) + '+', "~E1:k1:" + "A".repeat(37) + '/',
                "~E1:k1:" + "A".repeat(37) + '\u00e9')) {
            code(INVALID_ENVELOPE, () -> guarded.decrypt("default", "ns", "1", "secret", invalid));
        }
        assertEquals(0, lookups.get());
    }

    @Test void nonceCiphertextAndTagTamperingFailsAuthentication() {
        String encoded = engine.encrypt("default", "ns", "1", "secret", "sensitive");
        byte[] original = unpack(encoded);
        for (int position : List.of(0, 11, 12, original.length - 1)) {
            byte[] changed = original.clone();
            changed[position] ^= 1;
            String tampered = pack("k1", changed);
            code(AUTHENTICATION_FAILED, () -> engine.decrypt("default", "ns", "1", "secret", tampered));
        }
    }

    @Test void unknownKeyIsNotRetriedAndNeverFallsBackToCurrent() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        JcaFieldEncryption reader = new JcaFieldEncryption(new CacheKeyProvider() {
            public KeyMaterial current(String ring) { current.incrementAndGet(); return key(1); }
            public Optional<KeyMaterial> byId(String ring, String id) { reads.incrementAndGet(); return Optional.empty(); }
        });
        String unknown = engine.encrypt("default", "ns", "1", "secret", "value").replace("~E1:k1:", "~E1:missing:");
        code(UNKNOWN_KEY, () -> reader.decrypt("default", "ns", "1", "secret", unknown));
        assertEquals(1, reads.get());
        assertEquals(0, current.get());
    }

    @Test void keyRingAndKeyIdSelectionAreExplicitAndCaseSensitive() {
        JcaFieldEncryption isolated = new JcaFieldEncryption(new StaticKeyProvider(
                Map.of("default", List.of(key(1)), "other", List.of(new KeyMaterial("k1", aes(2), hmac()))),
                Map.of("default", "k1", "other", "k1")));
        String encoded = isolated.encrypt("default", "ns", "1", "secret", "value");
        code(AUTHENTICATION_FAILED, () -> isolated.decrypt("other", "ns", "1", "secret", encoded));
        code(UNKNOWN_KEY, () -> isolated.decrypt("Default", "ns", "1", "secret", encoded));
        code(UNKNOWN_KEY, () -> isolated.decrypt("default", "ns", "1", "secret", encoded.replace("~E1:k1:", "~E1:K1:")));
    }

    @Test void writesUseCurrentOnEveryCallAndOldReadsAndBlindIndexesSurviveRotation() {
        StaticKeyProvider before = provider("k1", key(1));
        StaticKeyProvider after = provider("k2", key(1), key(2));
        AtomicReference<CacheKeyProvider> live = new AtomicReference<>(before);
        JcaFieldEncryption rotating = new JcaFieldEncryption(new CacheKeyProvider() {
            public KeyMaterial current(String ring) { return live.get().current(ring); }
            public Optional<KeyMaterial> byId(String ring, String id) { return live.get().byId(ring, id); }
        });
        String old = rotating.encrypt("default", "ns", "1", "secret", "value");
        String oldToken = rotating.blindIndex("default", "ns", "secret", "value");
        live.set(after);
        assertEquals("value", rotating.decrypt("default", "ns", "1", "secret", old));
        assertTrue(rotating.encrypt("default", "ns", "1", "secret", "value").startsWith("~E1:k2:"));
        assertEquals(oldToken, rotating.blindIndex("default", "ns", "secret", "value"));
        JcaFieldEncryption retired = new JcaFieldEncryption(provider("k2", key(2)));
        code(UNKNOWN_KEY, () -> retired.decrypt("default", "ns", "1", "secret", old));
    }

    @Test void blindIndexUsesIndependentHmacKeyAndExactDomain() throws Exception {
        String token = engine.blindIndex("default", "ns", "secret", "a|b");
        assertTrue(token.startsWith("~B1:"));
        assertEquals(47, token.length());
        Mac reference = Mac.getInstance("HmacSHA256");
        reference.init(new SecretKeySpec(hmac(), "HmacSHA256"));
        byte[] expected = reference.doFinal("CacheDB:blind-index:v1|default|ns|secret|a|b".getBytes(StandardCharsets.UTF_8));
        assertEquals("~B1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(expected), token);
        reference.init(new SecretKeySpec(aes(1), "HmacSHA256"));
        assertNotEquals("~B1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(reference.doFinal(
                "CacheDB:blind-index:v1|default|ns|secret|a|b".getBytes(StandardCharsets.UTF_8))), token);
        assertNotEquals(token, engine.blindIndex("default", "other", "secret", "a|b"));
        assertNotEquals(token, engine.blindIndex("default", "ns", "other", "a|b"));
        assertNotEquals(token, engine.blindIndex("default", "ns", "secret", "A|b"));
        JcaFieldEncryption other = new JcaFieldEncryption(new StaticKeyProvider(
                Map.of("other", List.of(key(2))), Map.of("other", "k2")));
        assertNotEquals(token, other.blindIndex("other", "ns", "secret", "a|b"));
    }

    @Test void wireBudgetIncludesHeaderNonceTagAndUtf8Expansion() {
        for (String id : List.of("x", "x".repeat(64))) {
            JcaFieldEncryption bounded = new JcaFieldEncryption(provider(id, new KeyMaterial(id, aes(1), hmac())));
            int max = JcaFieldEncryption.maxPlaintextBytes(id);
            String value = "v".repeat(max);
            String encoded = bounded.encrypt("default", "ns", "1", "secret", value);
            assertTrue(encoded.length() <= 65_536);
            assertTrue(65_536 - encoded.length() < 2);
            assertEquals(value, bounded.decrypt("default", "ns", "1", "secret", encoded));
            code(FIELD_LIMIT, () -> bounded.encrypt("default", "ns", "1", "secret", value + 'v'));
            int triples = max / 3;
            String unicode = "\u20ac".repeat(triples) + "v".repeat(max % 3);
            assertEquals(unicode, bounded.decrypt("default", "ns", "1", "secret",
                    bounded.encrypt("default", "ns", "1", "secret", unicode)));
            code(FIELD_LIMIT, () -> bounded.encrypt("default", "ns", "1", "secret", unicode + 'v'));
        }
    }

    @Test void oversizedInputsAreRejectedBeforeProviderAndDoNotAllocateDecodedPayloads() {
        AtomicInteger lookups = new AtomicInteger();
        JcaFieldEncryption guarded = new JcaFieldEncryption(countingProvider(lookups));
        String tooLarge = "s".repeat(65_537);
        code(FIELD_LIMIT, () -> guarded.encrypt("default", "ns", "1", "secret", tooLarge));
        code(FIELD_LIMIT, () -> guarded.decrypt("default", "ns", "1", "secret", tooLarge));
        code(FIELD_LIMIT, () -> guarded.blindIndex("default", "ns", "secret", tooLarge));
        code(FIELD_LIMIT, () -> guarded.encrypt("default", "ns", "1", "secret", "\u20ac".repeat(21_846)));
        assertEquals(0, lookups.get());
        String largestTokenInput = "a".repeat(65_536);
        assertEquals(47, engine.blindIndex("default", "ns", "secret", largestTokenInput).length());
    }

    @Test void malformedUtf16NeverUsesReplacementEncoding() {
        for (String invalid : List.of("\ud800", "\udc00", "a\ud800b")) {
            code(INVALID_TEXT, () -> engine.encrypt("default", "ns", "1", "secret", invalid));
            code(INVALID_TEXT, () -> engine.blindIndex("default", "ns", "secret", invalid));
        }
    }

    @Test void authenticatedButInvalidUtf8IsRejected() throws Exception {
        String encoded = referenceEnvelope(new byte[12], new byte[] {(byte) 0xc3, (byte) 0x28});
        code(INVALID_TEXT, () -> engine.decrypt("default", "ns", "42", "secret", encoded));
    }

    @Test void providerFailuresAreRedactedIncludingCauseAndStackTrace() {
        String secret = "PII-MUST-NOT-APPEAR";
        JcaFieldEncryption broken = new JcaFieldEncryption(new CacheKeyProvider() {
            public KeyMaterial current(String ring) { throw new IllegalStateException(secret); }
            public Optional<KeyMaterial> byId(String ring, String id) { throw new IllegalStateException(secret); }
        });
        String encoded = engine.encrypt("default", "ns", "1", "secret", secret);
        List<Runnable> calls = List.of(() -> broken.encrypt("default", "ns", "1", "secret", secret),
                () -> broken.decrypt("default", "ns", "1", "secret", encoded),
                () -> broken.blindIndex("default", "ns", "secret", secret));
        for (Runnable call : calls) {
            CacheEncryptionException failure = code(PROVIDER_FAILURE, call);
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            assertFalse(trace.toString().contains(secret));
        }
    }

    @Test void providerContractViolationsFailClosed() {
        String encoded = engine.encrypt("default", "ns", "1", "secret", "value");
        JcaFieldEncryption nulls = new JcaFieldEncryption(new CacheKeyProvider() {
            public KeyMaterial current(String ring) { return null; }
            public Optional<KeyMaterial> byId(String ring, String id) { return null; }
        });
        for (int i = 0; i < 5; i++) {
            code(PROVIDER_FAILURE, () -> nulls.encrypt("default", "ns", "1", "secret", "value"));
            code(PROVIDER_FAILURE, () -> nulls.decrypt("default", "ns", "1", "secret", encoded));
            code(PROVIDER_FAILURE, () -> nulls.blindIndex("default", "ns", "secret", "value"));
        }
        JcaFieldEncryption wrongId = new JcaFieldEncryption(new CacheKeyProvider() {
            public KeyMaterial current(String ring) { return key(1); }
            public Optional<KeyMaterial> byId(String ring, String id) { return Optional.of(key(2)); }
        });
        code(PROVIDER_FAILURE, () -> wrongId.decrypt("default", "ns", "1", "secret", encoded));
    }

    @Test void wrongKeyBytesUnderKnownIdFailAuthentication() {
        String encoded = engine.encrypt("default", "ns", "1", "secret", "value");
        JcaFieldEncryption wrong = new JcaFieldEncryption(provider("k1", new KeyMaterial("k1", aes(2), hmac())));
        code(AUTHENTICATION_FAILED, () -> wrong.decrypt("default", "ns", "1", "secret", encoded));
    }

    @Test @Timeout(15) void saturationNeverWaitsAndEveryFailureReleasesItsPermit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean hold = new AtomicBoolean(true);
        JcaFieldEncryption bounded = new JcaFieldEncryption(new CacheKeyProvider() {
            public KeyMaterial current(String ring) {
                if (hold.get()) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("fixture interrupted");
                    }
                }
                return key(1);
            }
            public Optional<KeyMaterial> byId(String ring, String id) { return Optional.of(key(1)); }
        }, 1);
        String encoded = engine.encrypt("default", "ns", "1", "secret", "value");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() -> bounded.encrypt("default", "ns", "1", "secret", "value"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                code(SATURATED, () -> bounded.encrypt("default", "ns", "1", "secret", "value"));
                code(SATURATED, () -> bounded.decrypt("default", "ns", "1", "secret", encoded));
                code(SATURATED, () -> bounded.blindIndex("default", "ns", "secret", "value"));
            } finally {
                hold.set(false);
                release.countDown();
            }
            assertEquals("value", bounded.decrypt("default", "ns", "1", "secret", first.get(5, TimeUnit.SECONDS)));
        }
        byte[] changed = unpack(encoded);
        changed[0] ^= 1;
        code(AUTHENTICATION_FAILED, () -> bounded.decrypt("default", "ns", "1", "secret", pack("k1", changed)));
        code(UNKNOWN_KEY, () -> new JcaFieldEncryption(provider("k2", key(2)), 1)
                .decrypt("default", "ns", "1", "secret", encoded));
        assertEquals("value", bounded.decrypt("default", "ns", "1", "secret", encoded));
        assertEquals(47, bounded.blindIndex("default", "ns", "secret", "value").length());
    }

    @Test void providerFailureDoesNotLeakPermitsAcrossCalls() {
        AtomicBoolean fail = new AtomicBoolean(true);
        JcaFieldEncryption bounded = new JcaFieldEncryption(new CacheKeyProvider() {
            public KeyMaterial current(String ring) {
                if (fail.get()) throw new IllegalStateException("fixture failure");
                return key(1);
            }
            public Optional<KeyMaterial> byId(String ring, String id) { return Optional.of(key(1)); }
        }, 1);
        for (int i = 0; i < 5; i++) {
            code(PROVIDER_FAILURE, () -> bounded.encrypt("default", "ns", "1", "secret", "value"));
            code(PROVIDER_FAILURE, () -> bounded.blindIndex("default", "ns", "secret", "value"));
        }
        fail.set(false);
        String encoded = bounded.encrypt("default", "ns", "1", "secret", "value");
        assertEquals("value", bounded.decrypt("default", "ns", "1", "secret", encoded));
    }

    @Test void concurrencyAndConfigurationBudgetsAreHardLimits() {
        CacheKeyProvider keys = provider("k1", key(1));
        for (int invalid : List.of(-1, 0, 257, Integer.MAX_VALUE)) {
            code(INVALID_CONFIGURATION, () -> new JcaFieldEncryption(keys, invalid));
        }
        code(INVALID_CONFIGURATION, () -> new JcaFieldEncryption(null));
        assertNotNull(new JcaFieldEncryption(keys, 1));
        assertNotNull(new JcaFieldEncryption(keys, 256));
        for (String invalid : List.of("", "bad:val", "bad|val", "bad val", "\u0131", "x".repeat(65))) {
            code(INVALID_CONFIGURATION, () -> engine.encrypt(invalid, "ns", "1", "secret", "value"));
        }
    }

    @Test @Timeout(30) void thousandVirtualCallersUseUniqueNoncesAndIndependentCipherAndMacState() throws Exception {
        Set<String> nonces = ConcurrentHashMap.newKeySet();
        Semaphore admission = new Semaphore(32);
        String expectedToken = engine.blindIndex("default", "ns", "secret", "value");
        List<Future<Void>> calls = new ArrayList<>(1000);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1000; i++) {
                String id = Integer.toString(i);
                calls.add(executor.submit(() -> {
                    assertTrue(Thread.currentThread().isVirtual());
                    assertTrue(admission.tryAcquire(10, TimeUnit.SECONDS));
                    try {
                        String encoded = engine.encrypt("default", "ns", id, "secret", "value");
                        byte[] nonce = Arrays.copyOf(unpack(encoded), 12);
                        assertTrue(nonces.add(Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)));
                        assertEquals("value", engine.decrypt("default", "ns", id, "secret", encoded));
                        assertEquals(expectedToken, engine.blindIndex("default", "ns", "secret", "value"));
                    } finally { admission.release(); }
                    return null;
                }));
            }
            for (Future<Void> call : calls) call.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1000, nonces.size());
        assertEquals(32, admission.availablePermits());
    }

    @Test void repeatedWritesDoNotReuseNoncesEvenForTheSameRowAndPlaintext() {
        Set<String> nonces = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 2048; i++) {
            byte[] nonce = Arrays.copyOf(unpack(engine.encrypt("default", "ns", "1", "secret", "value")), 12);
            assertTrue(nonces.add(Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)));
        }
    }

    private static CacheEncryptionException code(CacheEncryptionException.Code expected, Runnable action) {
        CacheEncryptionException failure = assertThrows(CacheEncryptionException.class, action::run);
        assertEquals(expected, failure.code());
        assertEquals("CacheDB encryption: " + expected.name(), failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        return failure;
    }

    private static CacheKeyProvider countingProvider(AtomicInteger lookups) {
        return new CacheKeyProvider() {
            public KeyMaterial current(String ring) { lookups.incrementAndGet(); return key(1); }
            public Optional<KeyMaterial> byId(String ring, String id) { lookups.incrementAndGet(); return Optional.of(key(1)); }
        };
    }

    private static byte[] unpack(String envelope) {
        return Base64.getUrlDecoder().decode(envelope.substring(envelope.lastIndexOf(':') + 1));
    }

    private static String pack(String id, byte[] packed) {
        return "~E1:" + id + ':' + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
    }

    private static String referenceEnvelope(byte[] nonce, byte[] clear) throws Exception {
        Cipher reference = Cipher.getInstance("AES/GCM/NoPadding");
        reference.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aes(1), "AES"), new GCMParameterSpec(128, nonce));
        reference.updateAAD("ns|42|secret".getBytes(StandardCharsets.UTF_8));
        byte[] cipherTag = reference.doFinal(clear);
        byte[] packed = Arrays.copyOf(nonce, nonce.length + cipherTag.length);
        System.arraycopy(cipherTag, 0, packed, nonce.length, cipherTag.length);
        return pack("k1", packed);
    }
}

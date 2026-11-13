package com.example.cachedb.sample.encryption;

import static org.junit.jupiter.api.Assertions.*;
import com.example.cachedb.sample.ExternalSampleFixtures;
import com.reactor.cachedb.core.crypto.CacheEncryptionException;
import com.reactor.cachedb.redis.RedisKeyStrategy;
import com.reactor.cachedb.starter.CacheDatabase;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import redis.clients.jedis.JedisPooled;

@EnabledIfEnvironmentVariable(named = "CACHEDB_TEST_NAMESPACE", matches = "cachedb-[a-z0-9-]+")
@ActiveProfiles({"demo", "encryption"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sample.scheduled-warm.enabled=false", "sample.controls.supplier-token=supplier-test",
        "sample.controls.tenant-a-token=tenant-a-test", "sample.controls.tenant-b-token=tenant-b-test"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
class EncryptedNoteExternalIT {
    private static final String AES = randomKey(), HMAC = randomKey();
    private static String randomKey() { byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return Base64.getEncoder().encodeToString(value); }
    @DynamicPropertySource static void fixtures(DynamicPropertyRegistry registry) {
        if (!ExternalSampleFixtures.configure(registry)) throw new IllegalStateException("Dedicated provider fixtures required");
        registry.add("SAMPLE_ENCRYPTION_AES_KEY", () -> AES); registry.add("SAMPLE_ENCRYPTION_HMAC_KEY", () -> HMAC);
    }
    @Autowired EncryptedNotes notes;
    @Autowired CacheDatabase database;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;
    @Autowired @Qualifier("cacheDbJedisPooled") JedisPooled redis;

    @BeforeEach void finiteHttpTimeouts() {
        var requests = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(2000); requests.setReadTimeout(10000); rest.getRestTemplate().setRequestFactory(requests);
    }
    @Test void actualRedisCiphertextAndProviderSqlPlaintextRoundTripThroughTheBoundRuntime() {
        var row = new EncryptedNote("note-8", "fixture-" + java.util.UUID.randomUUID() + "-ğışöüİ");
        var receipt = notes.saveDurably(row, Duration.ofSeconds(10));
        assertTrue(notes.isDurable(receipt));
        var k = database.config().keyspace();
        var keys = new RedisKeyStrategy(k.keyPrefix(), k.entitySegment(), k.pageSegment(), k.versionSegment(),
                k.tombstoneSegment(), k.hotSetSegment(), k.indexSegment(), k.compactionSegment());
        String raw = redis.get(keys.entityKey("sample-encrypted-notes", row.id()));
        assertNotNull(raw); assertTrue(raw.contains("~E1:demo-v1:")); assertFalse(raw.contains(row.note()));
        var codec = database.runtimeCodec(EncryptedNoteCacheBinding.METADATA, EncryptedNoteCacheBinding.CODEC);
        assertTrue(codec.encryptionBound()); assertEquals(row, codec.fromRedisValue(raw));
        assertEquals(row, notes.findHotById(row.id()).value()); assertEquals(row, notes.findSourceById(row.id()).orElseThrow());
        assertEquals(row.note(), jdbc.queryForObject("SELECT note_value FROM sample_encrypted_notes WHERE note_id = ?", String.class, row.id()));
    }
    @Test void actualHttpUsesSupplierIdentityAndExplicitBoundedSourceConfirmation() {
        String path = "/api/examples/v019/encryption/notes/7";
        var headers = new HttpHeaders(); headers.setBearerAuth("supplier-test"); headers.setContentType(MediaType.APPLICATION_JSON);
        var result = rest.exchange(path + "/source-roundtrip", HttpMethod.POST,
                new HttpEntity<>(new EncryptedNoteController.Input("SQL and Redis: ğışöüİ"), headers), EncryptedNoteController.Output.class);
        assertEquals(200, result.getStatusCode().value()); assertTrue(result.getBody().sqlDurabilityConfirmed());
        assertEquals("SQL and Redis: ğışöüİ", result.getBody().note()); assertTrue(Long.parseLong(result.getBody().version()) > 0);
        headers.setBearerAuth("tenant-a-test");
        assertEquals(403, rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode().value());
        headers.setBearerAuth("supplier-test"); headers.set("X-CacheDB-Causal", "required");
        assertEquals(409, rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode().value());
    }
}

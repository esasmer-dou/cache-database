package com.example.cachedb.sample.encryption;

import com.reactor.cachedb.core.crypto.*;
import com.reactor.cachedb.starter.CacheDatabase;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("demo & encryption")
public class EncryptionConfiguration {
    @Bean
    @ConditionalOnMissingBean(CacheKeyProvider.class)
    CacheKeyProvider demoNoteKeys(Environment environment) {
        String aes = environment.getProperty("SAMPLE_ENCRYPTION_AES_KEY");
        String hmac = environment.getProperty("SAMPLE_ENCRYPTION_HMAC_KEY");
        if (aes == null || hmac == null)
            throw new IllegalStateException("Supply SAMPLE_ENCRYPTION_AES_KEY and SAMPLE_ENCRYPTION_HMAC_KEY");
        var key = KeyMaterial.fromBase64("demo-v1", aes, hmac);
        return new StaticKeyProvider(Map.of("sample-notes", List.of(key)), Map.of("sample-notes", "demo-v1"));
    }
    @Bean EncryptedNotes encryptedNotes(CacheDatabase database) {
        return new EncryptedNotesCacheDbImplementation(database);
    }
}

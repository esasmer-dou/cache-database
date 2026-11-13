package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.crypto.CacheKeyProvider;
import com.reactor.cachedb.core.crypto.EncryptedCodecSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Provider selection is explicit runtime configuration, never a base-record extension. */
@AutoConfiguration(before = CacheDatabaseSpringBootAutoConfiguration.class)
@ConditionalOnProperty(prefix = "cachedb", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheEncryptionAutoConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = "cacheKeyProviderRuntimeOptionsCustomizer")
    public CacheRuntimeOptionsCustomizer cacheKeyProviderRuntimeOptionsCustomizer(
            ObjectProvider<CacheKeyProvider> providers) {
        return (builder, properties) -> {
            CacheKeyProvider provider;
            try { provider = providers.getIfAvailable(); }
            catch (RuntimeException unsafe) { throw EncryptedCodecSupport.unbound(); }
            if (provider != null) builder.keyProvider(provider);
        };
    }
}

package com.reactor.cachedb.reactor;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

/** Optional module-local configuration; shared starter configuration remains untouched. */
@AutoConfiguration
@ConditionalOnClass(Mono.class)
public class CacheReactorAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ReactiveEntityRepositoryFactory.class)
    public ReactiveEntityRepositoryFactory reactiveEntityRepositoryFactory() {
        return new ReactiveEntityRepositoryFactory();
    }
}

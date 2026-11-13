package com.example.cachedb.purge;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Explicit startup barrier, shared with the runtime's defensive validation. */
@Configuration(proxyBeanMethods = false)
class TenantPurgeOptIn {
    @Bean
    static BeanFactoryPostProcessor purgeOptIn(Environment environment) {
        // Post-processors run before DataSource creation and SQL initialization.
        return beanFactory -> require(environment);
    }

    static String require(Environment environment) {
        String run = environment.getProperty("SAMPLE_PURGE_RUN_ID");
        if (run == null || !run.matches("[0-9a-f]{32}")
                || !"CREATE_FRESH_PURGE_DEMO".equals(environment.getProperty("SAMPLE_PURGE_CONFIRM")))
            throw new IllegalArgumentException("Fresh disposable purge opt-in required");
        return run;
    }
}

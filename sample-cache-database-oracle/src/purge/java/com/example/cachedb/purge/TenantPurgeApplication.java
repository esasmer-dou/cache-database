package com.example.cachedb.purge;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** Separate entry point: does not scan or register the legacy sample entities. */
@SpringBootConfiguration
@Import(TenantPurgeOptIn.class)
@EnableAutoConfiguration(exclude = com.reactor.cachedb.spring.boot.CacheDatabaseSpringBootAutoConfiguration.class)
public class TenantPurgeApplication {
    public static void main(String[] args) { SpringApplication.run(TenantPurgeApplication.class, args); }

    @Bean(destroyMethod = "close")
    @DependsOnDatabaseInitialization
    TenantPurgeRuntime tenantPurgeRuntime(DataSource source, Environment environment) {
        return new TenantPurgeRuntime(source, environment);
    }

    @Bean TenantPurgeHttp tenantPurgeHttp(TenantPurgeRuntime runtime, ObjectMapper mapper, Environment environment) {
        return new TenantPurgeHttp(runtime, mapper, environment.getRequiredProperty("SAMPLE_PURGE_ADMIN_TOKEN"),
                environment.getRequiredProperty("SAMPLE_PURGE_RUN_ID"));
    }
}

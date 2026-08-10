package com.reactor.cachedb.spring.boot;

import com.reactor.cachedb.core.config.AdminHttpConfig;
import com.reactor.cachedb.starter.CacheDatabase;
import com.reactor.cachedb.starter.CacheDatabaseAdminHttpServer;
import com.reactor.cachedb.starter.MigrationPlannerDemoSupport;
import jakarta.servlet.Servlet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = CacheDatabaseSpringBootAutoConfiguration.class)
@ConditionalOnClass({CacheDatabase.class, Servlet.class})
@ConditionalOnBean(CacheDatabase.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(CacheDbSpringProperties.class)
@ConditionalOnProperty(prefix = "cachedb", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheDatabaseAdminAutoConfiguration {

    @Bean
    @ConditionalOnBean(MigrationPlannerDemoSupport.class)
    public SmartInitializingSingleton cacheDatabaseMigrationPlannerDemoConfigurer(
            CacheDatabase cacheDatabase,
            ObjectProvider<MigrationPlannerDemoSupport> migrationPlannerDemoSupportProvider
    ) {
        return () -> {
            MigrationPlannerDemoSupport support = migrationPlannerDemoSupportProvider.getIfAvailable();
            if (support != null) {
                cacheDatabase.admin().configureMigrationPlannerDemo(support);
            }
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "cachedb.admin", name = "http-enabled", havingValue = "true")
    public CacheDatabaseAdminHttpServer cacheDatabaseSpringBootAdminHandler(
            CacheDatabase cacheDatabase,
            CacheDbSpringProperties properties
    ) {
        CacheDbSpringProperties.AdminUiProperties admin = properties.getAdmin();
        AdminHttpConfig config = AdminHttpConfig.builder()
                .enabled(false)
                .host("127.0.0.1")
                .port(0)
                .backlog(0)
                .workerThreads(1)
                .dashboardEnabled(admin.isDashboardEnabled())
                .dashboardTitle(admin.getTitle())
                .authEnabled(admin.isAuthEnabled())
                .authToken(admin.getAuthToken())
                .authHeaderName(admin.getAuthHeaderName())
                .requestQueueCapacity(admin.getRequestQueueCapacity())
                .backgroundWorkerThreads(admin.getBackgroundWorkerThreads())
                .backgroundQueueCapacity(admin.getBackgroundQueueCapacity())
                .maxRequestBodyBytes(admin.getMaxRequestBodyBytes())
                .jobStatusTtlSeconds(admin.getJobStatusTtlSeconds())
                .build();
        return cacheDatabase.adminHttpServer(config);
    }

    @Bean
    @ConditionalOnProperty(prefix = "cachedb.admin", name = "http-enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public CacheDatabaseAdminPageController cacheDatabaseAdminPageController(
            CacheDatabaseAdminHttpServer adminHandler,
            CacheDbSpringProperties properties
    ) {
        return new CacheDatabaseAdminPageController(adminHandler, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "cachedb.admin", name = "http-enabled", havingValue = "true")
    public ServletRegistrationBean<CacheDatabaseAdminServlet> cacheDatabaseAdminServlet(
            CacheDatabaseAdminHttpServer adminHandler,
            CacheDbSpringProperties properties
    ) {
        String basePath = normalizeBasePath(properties.getAdmin().getBasePath());
        CacheDatabaseAdminServlet servlet = new CacheDatabaseAdminServlet(adminHandler, basePath);
        ServletRegistrationBean<CacheDatabaseAdminServlet> bean = new ServletRegistrationBean<>(
                servlet,
                basePath.isBlank() ? "/api/*" : basePath + "/api/*"
        );
        bean.setName("cacheDbAdminServlet");
        bean.setLoadOnStartup(1);
        return bean;
    }

    private static String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return "/cachedb-admin";
        }
        String normalized = basePath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}

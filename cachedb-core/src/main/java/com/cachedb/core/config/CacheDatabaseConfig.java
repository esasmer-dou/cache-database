package com.reactor.cachedb.core.config;

public record CacheDatabaseConfig(
        WriteBehindConfig writeBehind,
        ResourceLimits resourceLimits,
        KeyspaceConfig keyspace,
        RuntimeCoordinationConfig runtimeCoordination,
        RedisFunctionsConfig redisFunctions,
        RelationConfig relations,
        PageCacheConfig pageCache,
        QueryIndexConfig queryIndex,
        ProjectionRefreshConfig projectionRefresh,
        RedisGuardrailConfig redisGuardrail,
        DeadLetterRecoveryConfig deadLetterRecovery,
        AdminMonitoringConfig adminMonitoring,
        AdminReportJobConfig adminReportJob,
        AdminHttpConfig adminHttp,
        SchemaBootstrapConfig schemaBootstrap
) {
    public static Builder builder() {
        return new Builder();
    }

    public static CacheDatabaseConfig defaults() {
        return builder().build();
    }

    public Builder toBuilder() {
        return builder()
                .writeBehind(writeBehind)
                .resourceLimits(resourceLimits)
                .keyspace(keyspace)
                .runtimeCoordination(runtimeCoordination)
                .redisFunctions(redisFunctions)
                .relations(relations)
                .pageCache(pageCache)
                .queryIndex(queryIndex)
                .projectionRefresh(projectionRefresh)
                .redisGuardrail(redisGuardrail)
                .deadLetterRecovery(deadLetterRecovery)
                .adminMonitoring(adminMonitoring)
                .adminReportJob(adminReportJob)
                .adminHttp(adminHttp)
                .schemaBootstrap(schemaBootstrap);
    }

    public static final class Builder {
        private WriteBehindConfig writeBehind = WriteBehindConfig.defaults();
        private ResourceLimits resourceLimits = ResourceLimits.defaults();
        private KeyspaceConfig keyspace = KeyspaceConfig.defaults();
        private RuntimeCoordinationConfig runtimeCoordination = RuntimeCoordinationConfig.defaults();
        private RedisFunctionsConfig redisFunctions = RedisFunctionsConfig.defaults();
        private RelationConfig relations = RelationConfig.defaults();
        private PageCacheConfig pageCache = PageCacheConfig.defaults();
        private QueryIndexConfig queryIndex = QueryIndexConfig.defaults();
        private ProjectionRefreshConfig projectionRefresh = ProjectionRefreshConfig.defaults();
        private RedisGuardrailConfig redisGuardrail = RedisGuardrailConfig.defaults();
        private DeadLetterRecoveryConfig deadLetterRecovery = DeadLetterRecoveryConfig.defaults();
        private AdminMonitoringConfig adminMonitoring = AdminMonitoringConfig.defaults();
        private AdminReportJobConfig adminReportJob = AdminReportJobConfig.defaults();
        private AdminHttpConfig adminHttp = AdminHttpConfig.defaults();
        private SchemaBootstrapConfig schemaBootstrap = SchemaBootstrapConfig.defaults();

        public Builder writeBehind(WriteBehindConfig writeBehind) {
            this.writeBehind = writeBehind;
            return this;
        }

        public Builder resourceLimits(ResourceLimits resourceLimits) {
            this.resourceLimits = resourceLimits;
            return this;
        }

        public Builder keyspace(KeyspaceConfig keyspace) {
            this.keyspace = keyspace;
            return this;
        }

        public Builder runtimeCoordination(RuntimeCoordinationConfig runtimeCoordination) {
            this.runtimeCoordination = runtimeCoordination;
            return this;
        }

        public Builder redisFunctions(RedisFunctionsConfig redisFunctions) {
            this.redisFunctions = redisFunctions;
            return this;
        }

        public Builder relations(RelationConfig relations) {
            this.relations = relations;
            return this;
        }

        public Builder pageCache(PageCacheConfig pageCache) {
            this.pageCache = pageCache;
            return this;
        }

        public Builder queryIndex(QueryIndexConfig queryIndex) {
            this.queryIndex = queryIndex;
            return this;
        }

        public Builder projectionRefresh(ProjectionRefreshConfig projectionRefresh) {
            this.projectionRefresh = projectionRefresh;
            return this;
        }

        public Builder redisGuardrail(RedisGuardrailConfig redisGuardrail) {
            this.redisGuardrail = redisGuardrail;
            return this;
        }

        public Builder deadLetterRecovery(DeadLetterRecoveryConfig deadLetterRecovery) {
            this.deadLetterRecovery = deadLetterRecovery;
            return this;
        }

        public Builder adminMonitoring(AdminMonitoringConfig adminMonitoring) {
            this.adminMonitoring = adminMonitoring;
            return this;
        }

        public Builder adminReportJob(AdminReportJobConfig adminReportJob) {
            this.adminReportJob = adminReportJob;
            return this;
        }

        public Builder adminHttp(AdminHttpConfig adminHttp) {
            this.adminHttp = adminHttp;
            return this;
        }

        public Builder schemaBootstrap(SchemaBootstrapConfig schemaBootstrap) {
            this.schemaBootstrap = schemaBootstrap;
            return this;
        }

        public CacheDatabaseConfig build() {
            return new CacheDatabaseConfig(
                    writeBehind,
                    resourceLimits,
                    keyspace,
                    runtimeCoordination,
                    redisFunctions,
                    relations,
                    pageCache,
                    queryIndex,
                    projectionRefresh,
                    redisGuardrail,
                    deadLetterRecovery,
                    adminMonitoring,
                    adminReportJob,
                    adminHttp,
                    schemaBootstrap
            );
        }
    }
}

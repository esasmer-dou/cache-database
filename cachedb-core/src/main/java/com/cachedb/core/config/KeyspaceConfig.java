package com.reactor.cachedb.core.config;

public record KeyspaceConfig(
        String keyPrefix,
        String entitySegment,
        String pageSegment,
        String versionSegment,
        String tombstoneSegment,
        String hotSetSegment,
        String indexSegment,
        String compactionSegment
) {
    public static Builder builder() {
        return new Builder();
    }

    public static KeyspaceConfig defaults() {
        return builder().build();
    }

    public Builder toBuilder() {
        return builder()
                .keyPrefix(keyPrefix)
                .entitySegment(entitySegment)
                .pageSegment(pageSegment)
                .versionSegment(versionSegment)
                .tombstoneSegment(tombstoneSegment)
                .hotSetSegment(hotSetSegment)
                .indexSegment(indexSegment)
                .compactionSegment(compactionSegment);
    }

    public static final class Builder {
        private String keyPrefix = "cachedb";
        private String entitySegment = "entity";
        private String pageSegment = "page";
        private String versionSegment = "version";
        private String tombstoneSegment = "tombstone";
        private String hotSetSegment = "hotset";
        private String indexSegment = "index";
        private String compactionSegment = "compaction";

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        public Builder entitySegment(String entitySegment) {
            this.entitySegment = entitySegment;
            return this;
        }

        public Builder pageSegment(String pageSegment) {
            this.pageSegment = pageSegment;
            return this;
        }

        public Builder versionSegment(String versionSegment) {
            this.versionSegment = versionSegment;
            return this;
        }

        public Builder tombstoneSegment(String tombstoneSegment) {
            this.tombstoneSegment = tombstoneSegment;
            return this;
        }

        public Builder hotSetSegment(String hotSetSegment) {
            this.hotSetSegment = hotSetSegment;
            return this;
        }

        public Builder indexSegment(String indexSegment) {
            this.indexSegment = indexSegment;
            return this;
        }

        public Builder compactionSegment(String compactionSegment) {
            this.compactionSegment = compactionSegment;
            return this;
        }

        public KeyspaceConfig build() {
            return new KeyspaceConfig(
                    keyPrefix,
                    entitySegment,
                    pageSegment,
                    versionSegment,
                    tombstoneSegment,
                    hotSetSegment,
                    indexSegment,
                    compactionSegment
            );
        }
    }
}

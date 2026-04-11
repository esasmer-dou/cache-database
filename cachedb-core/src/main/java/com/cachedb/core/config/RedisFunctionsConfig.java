package com.reactor.cachedb.core.config;

public record RedisFunctionsConfig(
        boolean enabled,
        boolean autoLoadLibrary,
        boolean replaceLibraryOnLoad,
        boolean strictLoading,
        String libraryName,
        String upsertFunctionName,
        String deleteFunctionName,
        String compactionCompleteFunctionName,
        String templateResourcePath,
        String sourceOverride
) {
    public static Builder builder() {
        return new Builder();
    }

    public static RedisFunctionsConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private boolean enabled = true;
        private boolean autoLoadLibrary = true;
        private boolean replaceLibraryOnLoad = true;
        private boolean strictLoading = true;
        private String libraryName = "cachedb";
        private String upsertFunctionName = "entity_upsert";
        private String deleteFunctionName = "entity_delete";
        private String compactionCompleteFunctionName = "compaction_complete";
        private String templateResourcePath = "/functions/cachedb-functions.lua";
        private String sourceOverride;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder autoLoadLibrary(boolean autoLoadLibrary) {
            this.autoLoadLibrary = autoLoadLibrary;
            return this;
        }

        public Builder replaceLibraryOnLoad(boolean replaceLibraryOnLoad) {
            this.replaceLibraryOnLoad = replaceLibraryOnLoad;
            return this;
        }

        public Builder strictLoading(boolean strictLoading) {
            this.strictLoading = strictLoading;
            return this;
        }

        public Builder libraryName(String libraryName) {
            this.libraryName = libraryName;
            return this;
        }

        public Builder upsertFunctionName(String upsertFunctionName) {
            this.upsertFunctionName = upsertFunctionName;
            return this;
        }

        public Builder deleteFunctionName(String deleteFunctionName) {
            this.deleteFunctionName = deleteFunctionName;
            return this;
        }

        public Builder compactionCompleteFunctionName(String compactionCompleteFunctionName) {
            this.compactionCompleteFunctionName = compactionCompleteFunctionName;
            return this;
        }

        public Builder templateResourcePath(String templateResourcePath) {
            this.templateResourcePath = templateResourcePath;
            return this;
        }

        public Builder sourceOverride(String sourceOverride) {
            this.sourceOverride = sourceOverride;
            return this;
        }

        public RedisFunctionsConfig build() {
            return new RedisFunctionsConfig(
                    enabled,
                    autoLoadLibrary,
                    replaceLibraryOnLoad,
                    strictLoading,
                    libraryName,
                    upsertFunctionName,
                    deleteFunctionName,
                    compactionCompleteFunctionName,
                    templateResourcePath,
                    sourceOverride
            );
        }
    }
}

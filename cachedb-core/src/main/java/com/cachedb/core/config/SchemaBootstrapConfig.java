package com.reactor.cachedb.core.config;

public record SchemaBootstrapConfig(
        SchemaBootstrapMode mode,
        boolean autoApplyOnStart,
        boolean includeVersionColumn,
        boolean includeDeletedColumn,
        String schemaName
) {
    public static Builder builder() {
        return new Builder();
    }

    public static SchemaBootstrapConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private SchemaBootstrapMode mode = SchemaBootstrapMode.DISABLED;
        private boolean autoApplyOnStart;
        private boolean includeVersionColumn = true;
        private boolean includeDeletedColumn = true;
        private String schemaName = "";

        public Builder mode(SchemaBootstrapMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder autoApplyOnStart(boolean autoApplyOnStart) {
            this.autoApplyOnStart = autoApplyOnStart;
            return this;
        }

        public Builder includeVersionColumn(boolean includeVersionColumn) {
            this.includeVersionColumn = includeVersionColumn;
            return this;
        }

        public Builder includeDeletedColumn(boolean includeDeletedColumn) {
            this.includeDeletedColumn = includeDeletedColumn;
            return this;
        }

        public Builder schemaName(String schemaName) {
            this.schemaName = schemaName;
            return this;
        }

        public SchemaBootstrapConfig build() {
            return new SchemaBootstrapConfig(
                    mode,
                    autoApplyOnStart,
                    includeVersionColumn,
                    includeDeletedColumn,
                    schemaName == null ? "" : schemaName.trim()
            );
        }
    }
}

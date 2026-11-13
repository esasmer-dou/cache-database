-- Retained security metadata. BLOB is bounded again by the application codec (4096 bytes).
CREATE TABLE cachedb_empty_activation (
    scope_token CHAR(64) NOT NULL,
    tenant_token CHAR(64) NOT NULL,
    operation_id VARCHAR2(36) NOT NULL,
    activation_id VARCHAR2(36) NOT NULL,
    payload BLOB NOT NULL,
    CONSTRAINT pk_cdb_empty_activation PRIMARY KEY (scope_token, tenant_token, operation_id),
    CONSTRAINT uq_cdb_empty_activation UNIQUE (scope_token, tenant_token, activation_id)
);

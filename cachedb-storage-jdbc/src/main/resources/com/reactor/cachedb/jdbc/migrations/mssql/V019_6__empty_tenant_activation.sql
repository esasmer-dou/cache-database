-- Retained security metadata; SQL guard serializes all checkpoint changes.
CREATE TABLE cachedb_empty_activation (
    scope_token CHAR(64) NOT NULL,
    tenant_token CHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    activation_id VARCHAR(36) NOT NULL,
    payload VARBINARY(4096) NOT NULL,
    CONSTRAINT pk_cdb_empty_activation PRIMARY KEY (scope_token, tenant_token, operation_id),
    CONSTRAINT uq_cdb_empty_activation UNIQUE (scope_token, tenant_token, activation_id),
    CONSTRAINT ck_cdb_empty_payload CHECK (DATALENGTH(payload) BETWEEN 1 AND 4096)
);

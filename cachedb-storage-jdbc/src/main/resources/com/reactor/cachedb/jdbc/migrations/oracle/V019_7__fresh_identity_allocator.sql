-- Retained namespace-global security metadata. NEVER purge, truncate, reset or cascade from tenant data.
-- Explicit admin domain provisioning only; no source MAX inference, runtime DDL or owner/write bypass.
CREATE TABLE cachedb_identity_allocator (
    scope_token CHAR(64) NOT NULL,
    binding_token CHAR(64) NOT NULL,
    authority_id VARCHAR2(36) NOT NULL,
    domain_first NUMBER(19) NOT NULL,
    domain_last NUMBER(19) NOT NULL,
    exclusive_token CHAR(64) NOT NULL,
    highwater NUMBER(19) NOT NULL,
    reservation_epoch NUMBER(19) NOT NULL,
    last_request VARCHAR2(36),
    last_tenant CHAR(64),
    last_generation NUMBER(19),
    last_control NUMBER(19),
    last_first NUMBER(19),
    last_count NUMBER(10),
    CONSTRAINT pk_cdb_identity_allocator PRIMARY KEY (scope_token),
    CONSTRAINT ck_cdb_identity_domain CHECK (domain_first >= 1 AND domain_last >= domain_first AND domain_last <= 9007199254740991),
    CONSTRAINT ck_cdb_identity_high CHECK (highwater >= domain_first - 1 AND highwater <= domain_last AND reservation_epoch BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_cdb_identity_receipt CHECK (
        (reservation_epoch = 0 AND highwater = domain_first - 1 AND last_request IS NULL AND last_tenant IS NULL
         AND last_generation IS NULL AND last_control IS NULL AND last_first IS NULL AND last_count IS NULL)
        OR (reservation_epoch > 0 AND last_request IS NOT NULL AND last_tenant IS NOT NULL
         AND last_generation IS NOT NULL AND last_control IS NOT NULL AND last_first IS NOT NULL AND last_count IS NOT NULL
         AND last_generation BETWEEN 1 AND 9007199254740991 AND last_control BETWEEN 1 AND 9007199254740991 AND last_first >= domain_first
         AND last_count BETWEEN 1 AND 1024 AND highwater - last_first = last_count - 1))
);
CREATE INDEX ix_cdb_namespace_scope ON cachedb_namespace_bootstrap (scope_token);

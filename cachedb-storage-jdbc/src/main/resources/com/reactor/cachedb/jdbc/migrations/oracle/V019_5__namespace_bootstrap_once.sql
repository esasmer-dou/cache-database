-- Explicit first namespace initialization only; retained security metadata. Never reset/recreate.
CREATE TABLE cachedb_namespace_bootstrap (
 roster_token VARCHAR(64) NOT NULL,
 binding_token VARCHAR(64) NOT NULL,
 mapping_token VARCHAR(64) NOT NULL,
 scope_token VARCHAR(64) NOT NULL,
 tenant_token VARCHAR(64) NOT NULL,
 generation NUMBER(19) NOT NULL,
 redis_run_id VARCHAR(40) NOT NULL,
 incarnation_id VARCHAR(36) NOT NULL,
 CONSTRAINT pk_cdb_namespace_boot PRIMARY KEY(roster_token),
 CONSTRAINT ck_cdb_namespace_gen CHECK(generation>0)
);

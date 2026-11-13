-- Explicit administrative installation; no runtime DDL or security-fence removal.
CREATE TABLE cachedb_initial_bootstrap (
 scope_token VARCHAR(64) NOT NULL,
 tenant_token VARCHAR(64) NOT NULL,
 operation_id VARCHAR(36) NOT NULL,
 generation BIGINT NOT NULL,
 plan_token VARCHAR(64) NOT NULL,
 manifest_token VARCHAR(64) NOT NULL,
 phase_name VARCHAR(32) NOT NULL,
 activation_token VARCHAR(64),
 revision BIGINT NOT NULL,
 payload VARBINARY(MAX) NOT NULL,
 payload_bytes BIGINT NOT NULL,
 CONSTRAINT pk_cdb_initial_boot PRIMARY KEY(scope_token,tenant_token),
 CONSTRAINT ck_cdb_initial_size CHECK(payload_bytes BETWEEN 1 AND 16384),
 CONSTRAINT ck_cdb_initial_rev CHECK(revision>=0 AND generation>0),
 CONSTRAINT ck_cdb_initial_phase CHECK(phase_name IN
 ('CLOSURE','NATIVE_INVENTORY','DURABLE_VERSIONS','SOURCE_VERSIONS','HOT_WINDOW',
  'VERIFY_WINDOW','READY','SQL_OPEN','ACTIVE','INCOMPLETE'))
);
CREATE TABLE cachedb_initial_page (
 scope_token VARCHAR(64) NOT NULL,
 tenant_token VARCHAR(64) NOT NULL,
 operation_id VARCHAR(36) NOT NULL,
 page_no BIGINT NOT NULL,
 payload VARBINARY(MAX) NOT NULL,
 payload_bytes BIGINT NOT NULL,
 is_applied BIGINT NOT NULL,
 is_verified BIGINT NOT NULL,
 CONSTRAINT pk_cdb_initial_page PRIMARY KEY(scope_token,tenant_token,operation_id,page_no),
 CONSTRAINT ck_cdb_initial_page_size CHECK(payload_bytes BETWEEN 1 AND 8388608),
 CONSTRAINT ck_cdb_initial_page_flags CHECK(is_applied IN(0,1) AND is_verified IN(0,1) AND page_no BETWEEN 0 AND 127)
);

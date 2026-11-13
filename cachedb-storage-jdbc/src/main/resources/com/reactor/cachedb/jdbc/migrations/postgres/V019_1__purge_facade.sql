-- Administrative temporary purge checkpoints; NEVER delete cachedb_tenant_guard/allocator/write_fence.
CREATE TABLE cachedb_purge_facade (
 scope_token VARCHAR(64) NOT NULL,
 tenant_token VARCHAR(64) NOT NULL,
 operation_id VARCHAR(36) NOT NULL,
 checkpoint_id VARCHAR(96) NOT NULL,
 revision BIGINT NOT NULL,
 cursor_value VARCHAR(1024),
 payload BYTEA NOT NULL,
 payload_bytes BIGINT NOT NULL,
 is_done BIGINT NOT NULL,
 evidence_token VARCHAR(64),
 examined_count BIGINT NOT NULL,
 removed_count BIGINT NOT NULL,
 CONSTRAINT pk_cdb_purge_facade PRIMARY KEY(scope_token,tenant_token,operation_id,checkpoint_id),
 CONSTRAINT ck_cdb_pf_bytes CHECK(payload_bytes BETWEEN 0 AND 1048576),
 CONSTRAINT ck_cdb_pf_done CHECK(is_done IN (0,1)),
 CONSTRAINT ck_cdb_pf_counts CHECK(revision>=0 AND examined_count>=0 AND removed_count>=0)
);
CREATE INDEX ix_cdb_pf_pending ON cachedb_purge_facade(scope_token,tenant_token,operation_id,is_done,checkpoint_id);

CREATE TABLE cachedb_purge_call (
 scope_token VARCHAR(64) NOT NULL,
 tenant_token VARCHAR(64) NOT NULL,
 operation_id VARCHAR(36) NOT NULL,
 lease_epoch BIGINT NOT NULL,
 call_id VARCHAR(36) NOT NULL,
 call_until TIMESTAMPTZ NOT NULL,
 CONSTRAINT pk_cdb_purge_call PRIMARY KEY(scope_token,tenant_token,operation_id)
);

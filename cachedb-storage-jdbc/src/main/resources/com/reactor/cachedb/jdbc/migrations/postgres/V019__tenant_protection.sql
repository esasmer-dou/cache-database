-- Durable security metadata; no runtime DDL or automatic fence deletion.
-- Install through the application migration system before opting in.
CREATE TABLE cachedb_tenant_guard (
    scope_token CHAR(64) NOT NULL,
    tenant_token CHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    gate_state VARCHAR(6) NOT NULL,
    operation_id VARCHAR(36) NULL,
    lease_epoch BIGINT DEFAULT 0 NOT NULL,
    lease_owner VARCHAR(36) NULL,
    lease_until TIMESTAMPTZ NULL,
    CONSTRAINT pk_tenant_guard PRIMARY KEY (scope_token, tenant_token),
    CONSTRAINT ck_guard_gen CHECK (generation > 0 AND lease_epoch >= 0),
    CONSTRAINT ck_guard_state CHECK (gate_state IN ('OPEN','CLOSED'))
);
CREATE TABLE cachedb_purge_progress (
    scope_token CHAR(64) NOT NULL,
    tenant_token CHAR(64) NOT NULL,
    payload BYTEA NOT NULL,
    CONSTRAINT pk_purge_progress PRIMARY KEY (scope_token, tenant_token),
    CONSTRAINT fk_purge_guard FOREIGN KEY (scope_token, tenant_token)
        REFERENCES cachedb_tenant_guard(scope_token, tenant_token),
    CONSTRAINT ck_purge_payload CHECK (octet_length(payload) <= 32768)
);
-- Scan frames and worklist are purge-only; no normal-write identity ledger.
CREATE TABLE cachedb_purge_scan (
    scope_token CHAR(64) NOT NULL,
    tenant_token CHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    source_token CHAR(64) NOT NULL,
    scan_kind VARCHAR(24) NOT NULL,
    locator BYTEA NOT NULL,
    scan_cursor VARCHAR(20) DEFAULT '0' NOT NULL,
    pending_id VARCHAR(36) NULL,
    pending_frame BYTEA NULL,
    next_cursor VARCHAR(20) NULL,
    item_count BIGINT DEFAULT 0 NOT NULL,
    pending_offset BIGINT DEFAULT 0 NOT NULL,
    is_complete SMALLINT DEFAULT 0 NOT NULL,
    is_failed SMALLINT DEFAULT 0 NOT NULL,
    topology_token CHAR(64) NULL,
    consumed_total BIGINT DEFAULT 0 NOT NULL,
    inventory_total BIGINT DEFAULT 0 NOT NULL,
    frame_finished SMALLINT DEFAULT 0 NOT NULL,
    CONSTRAINT pk_purge_scan PRIMARY KEY (scope_token,tenant_token,operation_id,source_token),
    CONSTRAINT fk_scan_guard FOREIGN KEY (scope_token,tenant_token)
        REFERENCES cachedb_tenant_guard(scope_token,tenant_token),
    CONSTRAINT ck_scan_kind CHECK (scan_kind IN ('OWNER','NAMESPACE','SET','ZSET','STREAM')),
    CONSTRAINT ck_scan_bounds CHECK (item_count BETWEEN 0 AND 4096 AND pending_offset BETWEEN 0 AND item_count),
    CONSTRAINT ck_scan_flags CHECK (is_complete IN (0,1) AND is_failed IN (0,1) AND frame_finished IN (0,1))
);
CREATE TABLE cachedb_purge_work_budget (
    scope_token CHAR(64) NOT NULL,
    tenant_token CHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    work_count BIGINT DEFAULT 0 NOT NULL,
    work_bytes BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT pk_purge_budget PRIMARY KEY (scope_token,tenant_token,operation_id),
    CONSTRAINT fk_budget_guard FOREIGN KEY (scope_token,tenant_token)
        REFERENCES cachedb_tenant_guard(scope_token,tenant_token),
    CONSTRAINT ck_purge_budget CHECK (work_count BETWEEN 0 AND 1000000 AND work_bytes BETWEEN 0 AND 104857600)
);
CREATE TABLE cachedb_purge_work (
    scope_token CHAR(64) NOT NULL,
    tenant_token CHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    work_token CHAR(64) NOT NULL,
    work_kind VARCHAR(24) NOT NULL,
    locator BYTEA NOT NULL,
    payload_bytes BIGINT NOT NULL,
    CONSTRAINT pk_purge_work PRIMARY KEY (scope_token,tenant_token,operation_id,work_token),
    CONSTRAINT fk_work_budget FOREIGN KEY (scope_token,tenant_token,operation_id)
        REFERENCES cachedb_purge_work_budget(scope_token,tenant_token,operation_id),
    CONSTRAINT ck_work_kind CHECK (work_kind IN ('ID','KEY','ENTRY')),
    CONSTRAINT ck_work_size CHECK (payload_bytes BETWEEN 1 AND 1048576)
);
ALTER TABLE cachedb_purge_scan ADD CONSTRAINT ck_scan_bytes CHECK
    (octet_length(locator) BETWEEN 1 AND 1024 AND (pending_frame IS NULL OR octet_length(pending_frame)<=1048576));
ALTER TABLE cachedb_purge_work ADD CONSTRAINT ck_work_bytes CHECK (octet_length(locator)=payload_bytes);
-- Oracle BLOB input/output is capped by the JDBC implementation before materialization.
-- Business tables need a leading tenant index; verify triggers/RLS have bounded same-tenant effects.
-- Retain existing cachedb_write_fence rows. No MAX-version sentinel or fence-table DELETE.
-- Grant security guard/progress metadata SELECT/INSERT/UPDATE only; no application DELETE privilege.

-- One retained allocator per namespace/tenant pair; no per-row identity reservation.
CREATE TABLE cachedb_tenant_allocator (
    scope_token CHAR(64) NOT NULL,
    tenant_token CHAR(64) NOT NULL,
    highwater BIGINT DEFAULT 0 NOT NULL,
    reservation_epoch BIGINT DEFAULT 0 NOT NULL,
    verified_generation BIGINT DEFAULT 0 NOT NULL,
    inventory_token CHAR(64) NULL,
    binding_token CHAR(64) NOT NULL,
    inventory_operation VARCHAR(36) NOT NULL,
    redis_max BIGINT DEFAULT 0 NOT NULL,
    sql_max BIGINT DEFAULT 0 NOT NULL,
    durable_cursor VARCHAR(64) DEFAULT '-' NOT NULL,
    source_complete SMALLINT DEFAULT 0 NOT NULL,
    durable_complete SMALLINT DEFAULT 0 NOT NULL,
    last_request VARCHAR(36) NULL,
    last_generation BIGINT NULL,
    last_control_epoch BIGINT NULL,
    last_first BIGINT NULL,
    last_last BIGINT NULL,
    last_run_id VARCHAR(40) NULL,
    CONSTRAINT pk_tenant_allocator PRIMARY KEY (scope_token,tenant_token),
    CONSTRAINT fk_allocator_guard FOREIGN KEY (scope_token,tenant_token)
        REFERENCES cachedb_tenant_guard(scope_token,tenant_token),
    CONSTRAINT ck_allocator_bounds CHECK (highwater BETWEEN 0 AND 9007199254740991
        AND redis_max BETWEEN 0 AND 9007199254740991 AND sql_max BETWEEN 0 AND 9007199254740991
        AND reservation_epoch>=0 AND verified_generation>=0 AND durable_complete IN (0,1) AND source_complete IN (0,1))
);

CREATE TABLE cachedb_purge_participant (
    scope_token VARCHAR(64) NOT NULL,
    tenant_token VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    phase_name VARCHAR(16) NOT NULL,
    declaration_token VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    step_state VARCHAR(16) NOT NULL,
    checkpoint_value VARCHAR(1024),
    examined_count BIGINT NOT NULL,
    removed_count BIGINT NOT NULL,
    evidence_token VARCHAR(64),
    CONSTRAINT pk_cachedb_purge_participant PRIMARY KEY
        (scope_token, tenant_token, operation_id, participant_id, phase_name),
    CONSTRAINT ck_cachedb_purge_part_count CHECK
        (revision>=0 AND examined_count>=0 AND removed_count>=0)
);
CREATE INDEX ix_cachedb_purge_work_kind ON cachedb_purge_work
    (scope_token, tenant_token, operation_id, work_kind, work_token);

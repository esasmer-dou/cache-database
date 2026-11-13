-- Durable security metadata; no runtime DDL or automatic fence deletion.
-- Install through the application migration system before opting in.
CREATE TABLE cachedb_tenant_guard (
    scope_token VARCHAR2(64 CHAR) NOT NULL,
    tenant_token VARCHAR2(64 CHAR) NOT NULL,
    generation NUMBER(19) NOT NULL,
    gate_state VARCHAR2(6 CHAR) NOT NULL,
    operation_id VARCHAR2(36 CHAR) NULL,
    lease_epoch NUMBER(19) DEFAULT 0 NOT NULL,
    lease_owner VARCHAR2(36 CHAR) NULL,
    lease_until TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT pk_tenant_guard PRIMARY KEY (scope_token, tenant_token),
    CONSTRAINT ck_guard_gen CHECK (generation > 0 AND lease_epoch >= 0),
    CONSTRAINT ck_guard_state CHECK (gate_state IN ('OPEN','CLOSED'))
);
CREATE TABLE cachedb_purge_progress (
    scope_token VARCHAR2(64 CHAR) NOT NULL,
    tenant_token VARCHAR2(64 CHAR) NOT NULL,
    payload BLOB NOT NULL,
    CONSTRAINT pk_purge_progress PRIMARY KEY (scope_token, tenant_token),
    CONSTRAINT fk_purge_guard FOREIGN KEY (scope_token, tenant_token)
        REFERENCES cachedb_tenant_guard(scope_token, tenant_token)
);
-- Scan frames and worklist are purge-only; no normal-write identity ledger.
CREATE TABLE cachedb_purge_scan (
    scope_token VARCHAR2(64) NOT NULL,
    tenant_token VARCHAR2(64) NOT NULL,
    operation_id VARCHAR2(36) NOT NULL,
    source_token VARCHAR2(64) NOT NULL,
    scan_kind VARCHAR2(24) NOT NULL,
    locator BLOB NOT NULL,
    scan_cursor VARCHAR2(20) DEFAULT '0' NOT NULL,
    pending_id VARCHAR2(36) NULL,
    pending_frame BLOB NULL,
    next_cursor VARCHAR2(20) NULL,
    item_count NUMBER(19) DEFAULT 0 NOT NULL,
    pending_offset NUMBER(19) DEFAULT 0 NOT NULL,
    is_complete NUMBER(1) DEFAULT 0 NOT NULL,
    is_failed NUMBER(1) DEFAULT 0 NOT NULL,
    topology_token VARCHAR2(64) NULL,
    consumed_total NUMBER(19) DEFAULT 0 NOT NULL,
    inventory_total NUMBER(19) DEFAULT 0 NOT NULL,
    frame_finished NUMBER(1) DEFAULT 0 NOT NULL,
    CONSTRAINT pk_purge_scan PRIMARY KEY (scope_token,tenant_token,operation_id,source_token),
    CONSTRAINT fk_scan_guard FOREIGN KEY (scope_token,tenant_token)
        REFERENCES cachedb_tenant_guard(scope_token,tenant_token),
    CONSTRAINT ck_scan_kind CHECK (scan_kind IN ('OWNER','NAMESPACE','SET','ZSET','STREAM')),
    CONSTRAINT ck_scan_bounds CHECK (item_count BETWEEN 0 AND 4096 AND pending_offset BETWEEN 0 AND item_count),
    CONSTRAINT ck_scan_flags CHECK (is_complete IN (0,1) AND is_failed IN (0,1) AND frame_finished IN (0,1))
);
CREATE TABLE cachedb_purge_work_budget (
    scope_token VARCHAR2(64) NOT NULL,
    tenant_token VARCHAR2(64) NOT NULL,
    operation_id VARCHAR2(36) NOT NULL,
    work_count NUMBER(19) DEFAULT 0 NOT NULL,
    work_bytes NUMBER(19) DEFAULT 0 NOT NULL,
    CONSTRAINT pk_purge_budget PRIMARY KEY (scope_token,tenant_token,operation_id),
    CONSTRAINT fk_budget_guard FOREIGN KEY (scope_token,tenant_token)
        REFERENCES cachedb_tenant_guard(scope_token,tenant_token),
    CONSTRAINT ck_purge_budget CHECK (work_count BETWEEN 0 AND 1000000 AND work_bytes BETWEEN 0 AND 104857600)
);
CREATE TABLE cachedb_purge_work (
    scope_token VARCHAR2(64) NOT NULL,
    tenant_token VARCHAR2(64) NOT NULL,
    operation_id VARCHAR2(36) NOT NULL,
    work_token VARCHAR2(64) NOT NULL,
    work_kind VARCHAR2(24) NOT NULL,
    locator BLOB NOT NULL,
    payload_bytes NUMBER(19) NOT NULL,
    CONSTRAINT pk_purge_work PRIMARY KEY (scope_token,tenant_token,operation_id,work_token),
    CONSTRAINT fk_work_budget FOREIGN KEY (scope_token,tenant_token,operation_id)
        REFERENCES cachedb_purge_work_budget(scope_token,tenant_token,operation_id),
    CONSTRAINT ck_work_kind CHECK (work_kind IN ('ID','KEY','ENTRY')),
    CONSTRAINT ck_work_size CHECK (payload_bytes BETWEEN 1 AND 1048576)
);
-- Oracle BLOB input/output is capped by the JDBC implementation before materialization.
-- Business tables need a leading tenant index; verify triggers/RLS have bounded same-tenant effects.
-- Retain existing cachedb_write_fence rows. No MAX-version sentinel or fence-table DELETE.
-- Grant security guard/progress metadata SELECT/INSERT/UPDATE only; no application DELETE privilege.

-- One retained allocator per namespace/tenant pair; no per-row identity reservation.
CREATE TABLE cachedb_tenant_allocator (
    scope_token VARCHAR2(64) NOT NULL,
    tenant_token VARCHAR2(64) NOT NULL,
    highwater NUMBER(19) DEFAULT 0 NOT NULL,
    reservation_epoch NUMBER(19) DEFAULT 0 NOT NULL,
    verified_generation NUMBER(19) DEFAULT 0 NOT NULL,
    inventory_token VARCHAR2(64) NULL,
    binding_token VARCHAR2(64) NOT NULL,
    inventory_operation VARCHAR2(36) NOT NULL,
    redis_max NUMBER(19) DEFAULT 0 NOT NULL,
    sql_max NUMBER(19) DEFAULT 0 NOT NULL,
    durable_cursor VARCHAR2(64) DEFAULT '-' NOT NULL,
    source_complete NUMBER(1) DEFAULT 0 NOT NULL,
    durable_complete NUMBER(1) DEFAULT 0 NOT NULL,
    last_request VARCHAR2(36) NULL,
    last_generation NUMBER(19) NULL,
    last_control_epoch NUMBER(19) NULL,
    last_first NUMBER(19) NULL,
    last_last NUMBER(19) NULL,
    last_run_id VARCHAR2(40) NULL,
    CONSTRAINT pk_tenant_allocator PRIMARY KEY (scope_token,tenant_token),
    CONSTRAINT fk_allocator_guard FOREIGN KEY (scope_token,tenant_token)
        REFERENCES cachedb_tenant_guard(scope_token,tenant_token),
    CONSTRAINT ck_allocator_bounds CHECK (highwater BETWEEN 0 AND 9007199254740991
        AND redis_max BETWEEN 0 AND 9007199254740991 AND sql_max BETWEEN 0 AND 9007199254740991
        AND reservation_epoch>=0 AND verified_generation>=0 AND durable_complete IN (0,1) AND source_complete IN (0,1))
);

CREATE TABLE cachedb_purge_participant (
    scope_token VARCHAR2(64 CHAR) NOT NULL,
    tenant_token VARCHAR2(64 CHAR) NOT NULL,
    operation_id VARCHAR2(36 CHAR) NOT NULL,
    participant_id VARCHAR2(64 CHAR) NOT NULL,
    phase_name VARCHAR2(16 CHAR) NOT NULL,
    declaration_token VARCHAR2(64 CHAR) NOT NULL,
    revision NUMBER(19) NOT NULL,
    step_state VARCHAR2(16 CHAR) NOT NULL,
    checkpoint_value VARCHAR2(1024 CHAR),
    examined_count NUMBER(19) NOT NULL,
    removed_count NUMBER(19) NOT NULL,
    evidence_token VARCHAR2(64 CHAR),
    CONSTRAINT pk_cachedb_purge_participant PRIMARY KEY
        (scope_token, tenant_token, operation_id, participant_id, phase_name),
    CONSTRAINT ck_cachedb_purge_part_count CHECK
        (revision>=0 AND examined_count>=0 AND removed_count>=0)
);
CREATE INDEX ix_cachedb_purge_work_kind ON cachedb_purge_work
    (scope_token, tenant_token, operation_id, work_kind, work_token);

BEGIN EXECUTE IMMEDIATE 'CREATE TABLE sample_range_rows (
    row_id VARCHAR2(96) PRIMARY KEY,
    tenant_id VARCHAR2(16) NOT NULL,
    run_id VARCHAR2(32) NOT NULL,
    status VARCHAR2(16) NOT NULL,
    rank_value NUMBER(19) NOT NULL,
    entity_version NUMBER(19) DEFAULT 0,
    deleted VARCHAR2(16)
)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@

BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_range_tenant ON sample_range_rows(tenant_id, run_id, status, rank_value, row_id)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@

CREATE TABLE IF NOT EXISTS sample_range_rows (
    row_id VARCHAR(96) PRIMARY KEY,
    tenant_id VARCHAR(16) NOT NULL,
    run_id VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    rank_value BIGINT NOT NULL,
    entity_version BIGINT DEFAULT 0,
    deleted VARCHAR(16)
);

CREATE INDEX IF NOT EXISTS idx_sample_range_tenant ON sample_range_rows(tenant_id, run_id, status, rank_value, row_id);

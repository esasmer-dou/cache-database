IF OBJECT_ID(N'sample_range_rows', N'U') IS NULL
EXEC(N'CREATE TABLE sample_range_rows (
    row_id NVARCHAR(96) PRIMARY KEY,
    tenant_id NVARCHAR(16) NOT NULL,
    run_id NVARCHAR(32) NOT NULL,
    status NVARCHAR(16) NOT NULL,
    rank_value BIGINT NOT NULL,
    entity_version BIGINT DEFAULT 0,
    deleted NVARCHAR(16)
)')
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_range_tenant' AND object_id = OBJECT_ID(N'sample_range_rows'))
CREATE INDEX idx_sample_range_tenant ON sample_range_rows(tenant_id, run_id, status, rank_value, row_id)
@@

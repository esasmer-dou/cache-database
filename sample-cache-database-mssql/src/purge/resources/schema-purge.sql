CREATE TABLE sample_purge_rows (
    id BIGINT NOT NULL PRIMARY KEY,
    tenant_id NVARCHAR(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    secret NVARCHAR(1024) NOT NULL,
    entity_version BIGINT NOT NULL
);
CREATE INDEX ix_sample_purge_tenant_id ON sample_purge_rows(tenant_id,id);
CREATE INDEX ix_sample_purge_tenant_version ON sample_purge_rows(tenant_id,entity_version);

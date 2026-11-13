CREATE TABLE sample_purge_rows (
    id NUMBER(19) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR2(64 CHAR) NOT NULL,
    secret VARCHAR2(1024 CHAR) NOT NULL,
    entity_version NUMBER(19) NOT NULL
);
CREATE INDEX ix_sample_purge_tenant_id ON sample_purge_rows(tenant_id,id);
CREATE INDEX ix_sample_purge_tenant_version ON sample_purge_rows(tenant_id,entity_version);

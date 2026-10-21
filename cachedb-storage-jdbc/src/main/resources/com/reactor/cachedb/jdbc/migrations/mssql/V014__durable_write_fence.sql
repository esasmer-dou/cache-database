-- Apply once in the write-behind datasource's default schema before starting workers.
-- Keep this table in backups. Do not truncate it or expire tombstones.
CREATE TABLE cachedb_write_fence (
    fence_key CHAR(64) NOT NULL PRIMARY KEY,
    identity_value NVARCHAR(MAX) NOT NULL,
    entity_version BIGINT,
    deleted SMALLINT NOT NULL
);

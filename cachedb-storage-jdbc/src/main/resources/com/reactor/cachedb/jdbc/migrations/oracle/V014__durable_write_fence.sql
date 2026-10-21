-- Apply once as the write-behind datasource owner before starting workers.
-- Oracle DDL commits implicitly: never execute this inside a business transaction.
-- Keep this table in backups. Do not truncate it or expire tombstones.
CREATE TABLE cachedb_write_fence (
    fence_key CHAR(64) NOT NULL PRIMARY KEY,
    identity_value CLOB NOT NULL,
    entity_version NUMBER(19),
    deleted SMALLINT NOT NULL
);

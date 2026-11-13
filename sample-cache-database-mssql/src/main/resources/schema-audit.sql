IF OBJECT_ID(N'sample_audit_evict', N'U') IS NULL
EXEC(N'CREATE TABLE sample_audit_evict (
    note_id NVARCHAR(16) PRIMARY KEY,
    note_value NVARCHAR(512) NOT NULL,
    created_on DATE NOT NULL,
    state NVARCHAR(16) NOT NULL,
    entity_version BIGINT DEFAULT 0,
    deleted NVARCHAR(16)
)')
@@
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_audit_evict_age' AND object_id = OBJECT_ID(N'sample_audit_evict'))
CREATE INDEX idx_sample_audit_evict_age ON sample_audit_evict(created_on, state)
@@

IF OBJECT_ID(N'sample_audit_delete', N'U') IS NULL
EXEC(N'CREATE TABLE sample_audit_delete (
    note_id NVARCHAR(16) PRIMARY KEY,
    note_value NVARCHAR(512) NOT NULL,
    created_on DATE NOT NULL,
    state NVARCHAR(16) NOT NULL,
    entity_version BIGINT DEFAULT 0,
    deleted NVARCHAR(16)
)')
@@
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_audit_delete_age' AND object_id = OBJECT_ID(N'sample_audit_delete'))
CREATE INDEX idx_sample_audit_delete_age ON sample_audit_delete(created_on, state)
@@

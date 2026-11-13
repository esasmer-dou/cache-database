IF OBJECT_ID(N'sample_encrypted_notes', N'U') IS NULL
EXEC(N'CREATE TABLE sample_encrypted_notes (
    note_id VARCHAR(32) PRIMARY KEY,
    note_value NVARCHAR(512) NOT NULL,
    entity_version BIGINT DEFAULT 0,
    deleted VARCHAR(16)
)')
@@

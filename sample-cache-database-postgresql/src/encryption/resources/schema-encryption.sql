CREATE TABLE IF NOT EXISTS sample_encrypted_notes (
    note_id VARCHAR(32) PRIMARY KEY,
    note_value VARCHAR(512) NOT NULL,
    entity_version BIGINT DEFAULT 0,
    deleted VARCHAR(16)
);

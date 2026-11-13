CREATE TABLE IF NOT EXISTS sample_audit_evict (
    note_id VARCHAR(16) PRIMARY KEY,
    note_value VARCHAR(512) NOT NULL,
    created_on DATE NOT NULL,
    state VARCHAR(16) NOT NULL,
    entity_version BIGINT DEFAULT 0,
    deleted VARCHAR(16)
);
CREATE INDEX IF NOT EXISTS idx_sample_audit_evict_age ON sample_audit_evict(created_on, state);

CREATE TABLE IF NOT EXISTS sample_audit_delete (
    note_id VARCHAR(16) PRIMARY KEY,
    note_value VARCHAR(512) NOT NULL,
    created_on DATE NOT NULL,
    state VARCHAR(16) NOT NULL,
    entity_version BIGINT DEFAULT 0,
    deleted VARCHAR(16)
);
CREATE INDEX IF NOT EXISTS idx_sample_audit_delete_age ON sample_audit_delete(created_on, state);

BEGIN EXECUTE IMMEDIATE 'CREATE TABLE sample_audit_evict (
    note_id VARCHAR2(16) PRIMARY KEY,
    note_value VARCHAR2(512 CHAR) NOT NULL,
    created_on DATE NOT NULL,
    state VARCHAR2(16) NOT NULL,
    entity_version NUMBER(19) DEFAULT 0,
    deleted VARCHAR2(16)
)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_audit_evict_age ON sample_audit_evict(created_on, state)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@

BEGIN EXECUTE IMMEDIATE 'CREATE TABLE sample_audit_delete (
    note_id VARCHAR2(16) PRIMARY KEY,
    note_value VARCHAR2(512 CHAR) NOT NULL,
    created_on DATE NOT NULL,
    state VARCHAR2(16) NOT NULL,
    entity_version NUMBER(19) DEFAULT 0,
    deleted VARCHAR2(16)
)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_audit_delete_age ON sample_audit_delete(created_on, state)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@

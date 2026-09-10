CREATE TABLE cachedb_outbox_adapter_checkpoint (
    adapter_name VARCHAR2(200 CHAR) NOT NULL PRIMARY KEY,
    last_event_id NUMBER(19) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

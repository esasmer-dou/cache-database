-- Explicit administrative installation only. One bounded checkpoint per namespace/tenant pair.
-- Does not change admission, generation/highwater, source rows or any retained security fence.
CREATE TABLE cachedb_owner_onboarding (
 scope_token VARCHAR2(64) NOT NULL,
 tenant_token VARCHAR2(64) NOT NULL,
 payload BLOB NOT NULL,
 payload_bytes NUMBER(10) NOT NULL,
 CONSTRAINT pk_cdb_owner_onboarding PRIMARY KEY(scope_token,tenant_token),
 CONSTRAINT ck_cdb_owner_bytes CHECK(payload_bytes BETWEEN 1 AND 262144)
);

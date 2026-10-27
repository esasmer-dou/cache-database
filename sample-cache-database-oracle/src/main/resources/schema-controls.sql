BEGIN EXECUTE IMMEDIATE 'CREATE TABLE sample_supplier_offers (
    offer_id NUMBER(19) PRIMARY KEY,
    supplier VARCHAR2(32) NOT NULL,
    status VARCHAR2(16) NOT NULL,
    unit_price NUMBER(19,4) NOT NULL,
    available_units NUMBER(10) NOT NULL,
    entity_version NUMBER(19) DEFAULT 0,
    deleted VARCHAR2(16)
)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@

BEGIN EXECUTE IMMEDIATE 'CREATE TABLE sample_tenant_stock (
    stock_id NUMBER(19) PRIMARY KEY,
    tenant_id VARCHAR2(32) NOT NULL,
    warehouse VARCHAR2(32) NOT NULL,
    status VARCHAR2(16) NOT NULL,
    available_units NUMBER(10) NOT NULL,
    entity_version NUMBER(19) DEFAULT 0,
    deleted VARCHAR2(16)
)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@

BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_offer_supplier ON sample_supplier_offers(supplier, status)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@

BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_stock_tenant ON sample_tenant_stock(tenant_id, warehouse, available_units)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@

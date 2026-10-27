CREATE TABLE IF NOT EXISTS sample_supplier_offers (
    offer_id BIGINT PRIMARY KEY,
    supplier VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    unit_price DECIMAL(19,4) NOT NULL,
    available_units INT NOT NULL,
    entity_version BIGINT DEFAULT 0,
    deleted VARCHAR(16)
);

CREATE TABLE IF NOT EXISTS sample_tenant_stock (
    stock_id BIGINT PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL,
    warehouse VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    available_units INT NOT NULL,
    entity_version BIGINT DEFAULT 0,
    deleted VARCHAR(16)
);

CREATE INDEX IF NOT EXISTS idx_sample_offer_supplier ON sample_supplier_offers(supplier, status);

CREATE INDEX IF NOT EXISTS idx_sample_stock_tenant ON sample_tenant_stock(tenant_id, warehouse, available_units);

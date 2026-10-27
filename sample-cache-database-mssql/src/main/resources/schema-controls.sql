IF OBJECT_ID(N'sample_supplier_offers', N'U') IS NULL
EXEC(N'CREATE TABLE sample_supplier_offers (
    offer_id BIGINT PRIMARY KEY,
    supplier NVARCHAR(32) NOT NULL,
    status NVARCHAR(16) NOT NULL,
    unit_price DECIMAL(19,4) NOT NULL,
    available_units INT NOT NULL,
    entity_version BIGINT DEFAULT 0,
    deleted NVARCHAR(16)
)')
@@

IF OBJECT_ID(N'sample_tenant_stock', N'U') IS NULL
EXEC(N'CREATE TABLE sample_tenant_stock (
    stock_id BIGINT PRIMARY KEY,
    tenant_id NVARCHAR(32) NOT NULL,
    warehouse NVARCHAR(32) NOT NULL,
    status NVARCHAR(16) NOT NULL,
    available_units INT NOT NULL,
    entity_version BIGINT DEFAULT 0,
    deleted NVARCHAR(16)
)')
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_offer_supplier' AND object_id = OBJECT_ID(N'sample_supplier_offers'))
CREATE INDEX idx_sample_offer_supplier ON sample_supplier_offers(supplier, status)
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_stock_tenant' AND object_id = OBJECT_ID(N'sample_tenant_stock'))
CREATE INDEX idx_sample_stock_tenant ON sample_tenant_stock(tenant_id, warehouse, available_units)
@@

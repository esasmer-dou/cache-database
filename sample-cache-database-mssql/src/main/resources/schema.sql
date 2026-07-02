IF OBJECT_ID(N'sample_customers', N'U') IS NULL
EXEC(N'CREATE TABLE sample_customers (
    customer_id BIGINT NOT NULL CONSTRAINT pk_sample_customers PRIMARY KEY,
    tax_number NVARCHAR(32) NOT NULL,
    customer_type NVARCHAR(24) NOT NULL,
    segment NVARCHAR(24) NOT NULL,
    status NVARCHAR(24) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
)')
@@

IF OBJECT_ID(N'sample_products', N'U') IS NULL
EXEC(N'CREATE TABLE sample_products (
    product_id BIGINT NOT NULL CONSTRAINT pk_sample_products PRIMARY KEY,
    sku NVARCHAR(64) NOT NULL CONSTRAINT uq_sample_products_sku UNIQUE,
    product_name NVARCHAR(160) NOT NULL,
    category NVARCHAR(64) NOT NULL,
    active_status NVARCHAR(16) NOT NULL,
    unit_price FLOAT NOT NULL,
    stock_quantity INT NOT NULL,
    reserved_quantity INT NOT NULL CONSTRAINT df_sample_products_reserved_quantity DEFAULT 0,
    stock_status NVARCHAR(24) NOT NULL CONSTRAINT df_sample_products_stock_status DEFAULT ''IN_STOCK'',
    updated_at BIGINT NOT NULL CONSTRAINT df_sample_products_updated_at DEFAULT 0
)')
@@

IF COL_LENGTH(N'sample_products', N'reserved_quantity') IS NULL
ALTER TABLE sample_products ADD reserved_quantity INT NOT NULL CONSTRAINT df_sample_products_reserved_quantity_added DEFAULT 0
@@

IF COL_LENGTH(N'sample_products', N'stock_status') IS NULL
ALTER TABLE sample_products ADD stock_status NVARCHAR(24) NOT NULL CONSTRAINT df_sample_products_stock_status_added DEFAULT 'IN_STOCK'
@@

IF COL_LENGTH(N'sample_products', N'updated_at') IS NULL
ALTER TABLE sample_products ADD updated_at BIGINT NOT NULL CONSTRAINT df_sample_products_updated_at_added DEFAULT 0
@@

IF OBJECT_ID(N'sample_orders', N'U') IS NULL
EXEC(N'CREATE TABLE sample_orders (
    order_id BIGINT NOT NULL CONSTRAINT pk_sample_orders PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    order_date BIGINT NOT NULL,
    order_amount FLOAT NOT NULL,
    currency_code NVARCHAR(8) NOT NULL,
    order_type NVARCHAR(32) NOT NULL,
    status NVARCHAR(24) NOT NULL,
    line_count INT NOT NULL,
    priority_score FLOAT NOT NULL,
    CONSTRAINT fk_sample_orders_customer FOREIGN KEY (customer_id) REFERENCES sample_customers(customer_id)
)')
@@

IF OBJECT_ID(N'sample_order_lines', N'U') IS NULL
EXEC(N'CREATE TABLE sample_order_lines (
    line_id BIGINT NOT NULL CONSTRAINT pk_sample_order_lines PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    line_number INT NOT NULL,
    sku NVARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    unit_price FLOAT NOT NULL,
    line_total FLOAT NOT NULL,
    status NVARCHAR(24) NOT NULL,
    CONSTRAINT fk_sample_order_lines_order FOREIGN KEY (order_id) REFERENCES sample_orders(order_id),
    CONSTRAINT fk_sample_order_lines_product FOREIGN KEY (product_id) REFERENCES sample_products(product_id)
)')
@@

IF OBJECT_ID(N'sample_support_tickets', N'U') IS NULL
EXEC(N'CREATE TABLE sample_support_tickets (
    ticket_id BIGINT NOT NULL CONSTRAINT pk_sample_support_tickets PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    priority NVARCHAR(16) NOT NULL,
    status NVARCHAR(24) NOT NULL,
    subject NVARCHAR(180) NOT NULL,
    opened_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_sample_tickets_customer FOREIGN KEY (customer_id) REFERENCES sample_customers(customer_id)
)')
@@

IF OBJECT_ID(N'sample_shipments', N'U') IS NULL
EXEC(N'CREATE TABLE sample_shipments (
    shipment_id BIGINT NOT NULL CONSTRAINT pk_sample_shipments PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    tracking_number NVARCHAR(80) NOT NULL CONSTRAINT uq_sample_shipments_tracking UNIQUE,
    carrier_code NVARCHAR(24) NOT NULL,
    shipment_status NVARCHAR(32) NOT NULL,
    current_city NVARCHAR(80) NOT NULL,
    promised_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    risk_score FLOAT NOT NULL,
    CONSTRAINT fk_sample_shipments_customer FOREIGN KEY (customer_id) REFERENCES sample_customers(customer_id)
)')
@@

IF OBJECT_ID(N'sample_shipment_events', N'U') IS NULL
EXEC(N'CREATE TABLE sample_shipment_events (
    event_id BIGINT NOT NULL CONSTRAINT pk_sample_shipment_events PRIMARY KEY,
    shipment_id BIGINT NOT NULL,
    event_type NVARCHAR(40) NOT NULL,
    event_city NVARCHAR(80) NOT NULL,
    event_time BIGINT NOT NULL,
    severity NVARCHAR(16) NOT NULL,
    description NVARCHAR(240) NOT NULL,
    CONSTRAINT fk_sample_shipment_events_shipment FOREIGN KEY (shipment_id) REFERENCES sample_shipments(shipment_id)
)')
@@

IF OBJECT_ID(N'sample_report_jobs', N'U') IS NULL
EXEC(N'CREATE TABLE sample_report_jobs (
    report_job_id BIGINT NOT NULL CONSTRAINT pk_sample_report_jobs PRIMARY KEY,
    report_type NVARCHAR(40) NOT NULL,
    status NVARCHAR(24) NOT NULL,
    requested_by NVARCHAR(120) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    row_count INT NOT NULL,
    failure_reason NVARCHAR(240) NULL
)')
@@

IF OBJECT_ID(N'sample_audit_events', N'U') IS NULL
EXEC(N'CREATE TABLE sample_audit_events (
    audit_event_id BIGINT NOT NULL CONSTRAINT pk_sample_audit_events PRIMARY KEY,
    entity_name NVARCHAR(80) NOT NULL,
    entity_id BIGINT NOT NULL,
    event_type NVARCHAR(40) NOT NULL,
    severity NVARCHAR(16) NOT NULL,
    actor NVARCHAR(120) NOT NULL,
    created_at BIGINT NOT NULL,
    message NVARCHAR(240) NOT NULL
)')
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_orders_customer_date' AND object_id = OBJECT_ID(N'sample_orders'))
CREATE INDEX idx_sample_orders_customer_date ON sample_orders(customer_id, order_date DESC, order_id DESC)
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_orders_priority' AND object_id = OBJECT_ID(N'sample_orders'))
CREATE INDEX idx_sample_orders_priority ON sample_orders(priority_score DESC, order_date DESC)
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_order_lines_order_number' AND object_id = OBJECT_ID(N'sample_order_lines'))
CREATE INDEX idx_sample_order_lines_order_number ON sample_order_lines(order_id, line_number ASC)
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_tickets_customer_status' AND object_id = OBJECT_ID(N'sample_support_tickets'))
CREATE INDEX idx_sample_tickets_customer_status ON sample_support_tickets(customer_id, status)
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_tickets_status_priority' AND object_id = OBJECT_ID(N'sample_support_tickets'))
CREATE INDEX idx_sample_tickets_status_priority ON sample_support_tickets(status, priority, updated_at DESC)
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_products_category_stock' AND object_id = OBJECT_ID(N'sample_products'))
CREATE INDEX idx_sample_products_category_stock ON sample_products(category, active_status, stock_status, updated_at DESC)
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_shipments_active' AND object_id = OBJECT_ID(N'sample_shipments'))
CREATE INDEX idx_sample_shipments_active ON sample_shipments(shipment_status, risk_score DESC, updated_at DESC)
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_shipments_customer_updated' AND object_id = OBJECT_ID(N'sample_shipments'))
CREATE INDEX idx_sample_shipments_customer_updated ON sample_shipments(customer_id, updated_at DESC, shipment_id DESC)
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_shipment_events_shipment_time' AND object_id = OBJECT_ID(N'sample_shipment_events'))
CREATE INDEX idx_sample_shipment_events_shipment_time ON sample_shipment_events(shipment_id, event_time DESC, event_id DESC)
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_report_jobs_live' AND object_id = OBJECT_ID(N'sample_report_jobs'))
CREATE INDEX idx_sample_report_jobs_live ON sample_report_jobs(status, updated_at DESC, report_job_id DESC)
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_audit_events_entity_time' AND object_id = OBJECT_ID(N'sample_audit_events'))
CREATE INDEX idx_sample_audit_events_entity_time ON sample_audit_events(entity_name, entity_id, created_at DESC)
@@

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_sample_audit_events_security' AND object_id = OBJECT_ID(N'sample_audit_events'))
CREATE INDEX idx_sample_audit_events_security ON sample_audit_events(severity, created_at DESC)
@@

IF COL_LENGTH(N'sample_customers', N'entity_version') IS NULL
ALTER TABLE sample_customers ADD entity_version BIGINT NULL CONSTRAINT df_sample_customers_entity_version DEFAULT 0
@@
IF COL_LENGTH(N'sample_customers', N'deleted') IS NULL
ALTER TABLE sample_customers ADD deleted NVARCHAR(16) NULL
@@
IF COL_LENGTH(N'sample_products', N'entity_version') IS NULL
ALTER TABLE sample_products ADD entity_version BIGINT NULL CONSTRAINT df_sample_products_entity_version DEFAULT 0
@@
IF COL_LENGTH(N'sample_products', N'deleted') IS NULL
ALTER TABLE sample_products ADD deleted NVARCHAR(16) NULL
@@
IF COL_LENGTH(N'sample_orders', N'entity_version') IS NULL
ALTER TABLE sample_orders ADD entity_version BIGINT NULL CONSTRAINT df_sample_orders_entity_version DEFAULT 0
@@
IF COL_LENGTH(N'sample_orders', N'deleted') IS NULL
ALTER TABLE sample_orders ADD deleted NVARCHAR(16) NULL
@@
IF COL_LENGTH(N'sample_order_lines', N'entity_version') IS NULL
ALTER TABLE sample_order_lines ADD entity_version BIGINT NULL CONSTRAINT df_sample_order_lines_entity_version DEFAULT 0
@@
IF COL_LENGTH(N'sample_order_lines', N'deleted') IS NULL
ALTER TABLE sample_order_lines ADD deleted NVARCHAR(16) NULL
@@
IF COL_LENGTH(N'sample_support_tickets', N'entity_version') IS NULL
ALTER TABLE sample_support_tickets ADD entity_version BIGINT NULL CONSTRAINT df_sample_support_tickets_entity_version DEFAULT 0
@@
IF COL_LENGTH(N'sample_support_tickets', N'deleted') IS NULL
ALTER TABLE sample_support_tickets ADD deleted NVARCHAR(16) NULL
@@
IF COL_LENGTH(N'sample_shipments', N'entity_version') IS NULL
ALTER TABLE sample_shipments ADD entity_version BIGINT NULL CONSTRAINT df_sample_shipments_entity_version DEFAULT 0
@@
IF COL_LENGTH(N'sample_shipments', N'deleted') IS NULL
ALTER TABLE sample_shipments ADD deleted NVARCHAR(16) NULL
@@
IF COL_LENGTH(N'sample_shipment_events', N'entity_version') IS NULL
ALTER TABLE sample_shipment_events ADD entity_version BIGINT NULL CONSTRAINT df_sample_shipment_events_entity_version DEFAULT 0
@@
IF COL_LENGTH(N'sample_shipment_events', N'deleted') IS NULL
ALTER TABLE sample_shipment_events ADD deleted NVARCHAR(16) NULL
@@
IF COL_LENGTH(N'sample_report_jobs', N'entity_version') IS NULL
ALTER TABLE sample_report_jobs ADD entity_version BIGINT NULL CONSTRAINT df_sample_report_jobs_entity_version DEFAULT 0
@@
IF COL_LENGTH(N'sample_report_jobs', N'deleted') IS NULL
ALTER TABLE sample_report_jobs ADD deleted NVARCHAR(16) NULL
@@
IF COL_LENGTH(N'sample_audit_events', N'entity_version') IS NULL
ALTER TABLE sample_audit_events ADD entity_version BIGINT NULL CONSTRAINT df_sample_audit_events_entity_version DEFAULT 0
@@
IF COL_LENGTH(N'sample_audit_events', N'deleted') IS NULL
ALTER TABLE sample_audit_events ADD deleted NVARCHAR(16) NULL
@@

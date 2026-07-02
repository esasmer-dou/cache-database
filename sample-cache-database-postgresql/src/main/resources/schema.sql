CREATE TABLE IF NOT EXISTS sample_customers (
    customer_id BIGINT PRIMARY KEY,
    tax_number VARCHAR(32) NOT NULL,
    customer_type VARCHAR(24) NOT NULL,
    segment VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS sample_products (
    product_id BIGINT PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    product_name VARCHAR(160) NOT NULL,
    category VARCHAR(64) NOT NULL,
    active_status VARCHAR(16) NOT NULL,
    unit_price DOUBLE PRECISION NOT NULL,
    stock_quantity INT NOT NULL,
    reserved_quantity INT NOT NULL DEFAULT 0,
    stock_status VARCHAR(24) NOT NULL DEFAULT 'IN_STOCK',
    updated_at BIGINT NOT NULL DEFAULT 0
);

ALTER TABLE sample_products ADD COLUMN IF NOT EXISTS reserved_quantity INT NOT NULL DEFAULT 0;
ALTER TABLE sample_products ADD COLUMN IF NOT EXISTS stock_status VARCHAR(24) NOT NULL DEFAULT 'IN_STOCK';
ALTER TABLE sample_products ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS sample_orders (
    order_id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES sample_customers(customer_id),
    order_date BIGINT NOT NULL,
    order_amount DOUBLE PRECISION NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    order_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    line_count INT NOT NULL,
    priority_score DOUBLE PRECISION NOT NULL
);

CREATE TABLE IF NOT EXISTS sample_order_lines (
    line_id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES sample_orders(order_id),
    product_id BIGINT NOT NULL REFERENCES sample_products(product_id),
    line_number INT NOT NULL,
    sku VARCHAR(64) NOT NULL,
    quantity INT NOT NULL,
    unit_price DOUBLE PRECISION NOT NULL,
    line_total DOUBLE PRECISION NOT NULL,
    status VARCHAR(24) NOT NULL
);

CREATE TABLE IF NOT EXISTS sample_support_tickets (
    ticket_id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES sample_customers(customer_id),
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    subject VARCHAR(180) NOT NULL,
    opened_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS sample_shipments (
    shipment_id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES sample_customers(customer_id),
    tracking_number VARCHAR(80) NOT NULL UNIQUE,
    carrier_code VARCHAR(24) NOT NULL,
    shipment_status VARCHAR(32) NOT NULL,
    current_city VARCHAR(80) NOT NULL,
    promised_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    risk_score DOUBLE PRECISION NOT NULL
);

CREATE TABLE IF NOT EXISTS sample_shipment_events (
    event_id BIGINT PRIMARY KEY,
    shipment_id BIGINT NOT NULL REFERENCES sample_shipments(shipment_id),
    event_type VARCHAR(40) NOT NULL,
    event_city VARCHAR(80) NOT NULL,
    event_time BIGINT NOT NULL,
    severity VARCHAR(16) NOT NULL,
    description VARCHAR(240) NOT NULL
);

CREATE TABLE IF NOT EXISTS sample_report_jobs (
    report_job_id BIGINT PRIMARY KEY,
    report_type VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    requested_by VARCHAR(120) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    row_count INT NOT NULL,
    failure_reason VARCHAR(240)
);

CREATE TABLE IF NOT EXISTS sample_audit_events (
    audit_event_id BIGINT PRIMARY KEY,
    entity_name VARCHAR(80) NOT NULL,
    entity_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    actor VARCHAR(120) NOT NULL,
    created_at BIGINT NOT NULL,
    message VARCHAR(240) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sample_orders_customer_date ON sample_orders(customer_id, order_date DESC, order_id DESC);
CREATE INDEX IF NOT EXISTS idx_sample_orders_priority ON sample_orders(priority_score DESC, order_date DESC);
CREATE INDEX IF NOT EXISTS idx_sample_order_lines_order_number ON sample_order_lines(order_id, line_number ASC);
CREATE INDEX IF NOT EXISTS idx_sample_tickets_customer_status ON sample_support_tickets(customer_id, status);
CREATE INDEX IF NOT EXISTS idx_sample_tickets_status_priority ON sample_support_tickets(status, priority, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_sample_products_category_stock ON sample_products(category, active_status, stock_status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_sample_shipments_active ON sample_shipments(shipment_status, risk_score DESC, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_sample_shipments_customer_updated ON sample_shipments(customer_id, updated_at DESC, shipment_id DESC);
CREATE INDEX IF NOT EXISTS idx_sample_shipment_events_shipment_time ON sample_shipment_events(shipment_id, event_time DESC, event_id DESC);
CREATE INDEX IF NOT EXISTS idx_sample_report_jobs_live ON sample_report_jobs(status, updated_at DESC, report_job_id DESC);
CREATE INDEX IF NOT EXISTS idx_sample_audit_events_entity_time ON sample_audit_events(entity_name, entity_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sample_audit_events_security ON sample_audit_events(severity, created_at DESC);

ALTER TABLE sample_customers ADD COLUMN IF NOT EXISTS entity_version BIGINT DEFAULT 0;
ALTER TABLE sample_customers ADD COLUMN IF NOT EXISTS deleted VARCHAR(16);
ALTER TABLE sample_products ADD COLUMN IF NOT EXISTS entity_version BIGINT DEFAULT 0;
ALTER TABLE sample_products ADD COLUMN IF NOT EXISTS deleted VARCHAR(16);
ALTER TABLE sample_orders ADD COLUMN IF NOT EXISTS entity_version BIGINT DEFAULT 0;
ALTER TABLE sample_orders ADD COLUMN IF NOT EXISTS deleted VARCHAR(16);
ALTER TABLE sample_order_lines ADD COLUMN IF NOT EXISTS entity_version BIGINT DEFAULT 0;
ALTER TABLE sample_order_lines ADD COLUMN IF NOT EXISTS deleted VARCHAR(16);
ALTER TABLE sample_support_tickets ADD COLUMN IF NOT EXISTS entity_version BIGINT DEFAULT 0;
ALTER TABLE sample_support_tickets ADD COLUMN IF NOT EXISTS deleted VARCHAR(16);
ALTER TABLE sample_shipments ADD COLUMN IF NOT EXISTS entity_version BIGINT DEFAULT 0;
ALTER TABLE sample_shipments ADD COLUMN IF NOT EXISTS deleted VARCHAR(16);
ALTER TABLE sample_shipment_events ADD COLUMN IF NOT EXISTS entity_version BIGINT DEFAULT 0;
ALTER TABLE sample_shipment_events ADD COLUMN IF NOT EXISTS deleted VARCHAR(16);
ALTER TABLE sample_report_jobs ADD COLUMN IF NOT EXISTS entity_version BIGINT DEFAULT 0;
ALTER TABLE sample_report_jobs ADD COLUMN IF NOT EXISTS deleted VARCHAR(16);
ALTER TABLE sample_audit_events ADD COLUMN IF NOT EXISTS entity_version BIGINT DEFAULT 0;
ALTER TABLE sample_audit_events ADD COLUMN IF NOT EXISTS deleted VARCHAR(16);

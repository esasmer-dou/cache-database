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
    stock_quantity INT NOT NULL
);

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

CREATE INDEX IF NOT EXISTS idx_sample_orders_customer_date ON sample_orders(customer_id, order_date DESC, order_id DESC);
CREATE INDEX IF NOT EXISTS idx_sample_orders_priority ON sample_orders(priority_score DESC, order_date DESC);
CREATE INDEX IF NOT EXISTS idx_sample_order_lines_order_number ON sample_order_lines(order_id, line_number ASC);
CREATE INDEX IF NOT EXISTS idx_sample_tickets_customer_status ON sample_support_tickets(customer_id, status);
CREATE INDEX IF NOT EXISTS idx_sample_tickets_status_priority ON sample_support_tickets(status, priority, updated_at DESC);

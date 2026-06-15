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
    stock_quantity INT NOT NULL
)')
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

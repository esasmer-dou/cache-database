BEGIN
    EXECUTE IMMEDIATE 'CREATE TABLE sample_customers (
        customer_id NUMBER(19) CONSTRAINT pk_sample_customers PRIMARY KEY,
        tax_number VARCHAR2(32) NOT NULL,
        customer_type VARCHAR2(24) NOT NULL,
        segment VARCHAR2(24) NOT NULL,
        status VARCHAR2(24) NOT NULL,
        created_at NUMBER(19) NOT NULL,
        updated_at NUMBER(19) NOT NULL,
        entity_version NUMBER(19) DEFAULT 0,
        deleted VARCHAR2(16)
    )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
@@

BEGIN
    EXECUTE IMMEDIATE 'CREATE TABLE sample_products (
        product_id NUMBER(19) CONSTRAINT pk_sample_products PRIMARY KEY,
        sku VARCHAR2(64) CONSTRAINT uq_sample_products_sku UNIQUE NOT NULL,
        product_name VARCHAR2(160) NOT NULL,
        category VARCHAR2(64) NOT NULL,
        active_status VARCHAR2(16) NOT NULL,
        unit_price NUMBER(19, 4) NOT NULL,
        stock_quantity NUMBER(10) NOT NULL,
        reserved_quantity NUMBER(10) DEFAULT 0 NOT NULL,
        stock_status VARCHAR2(24) DEFAULT ''IN_STOCK'' NOT NULL,
        updated_at NUMBER(19) DEFAULT 0 NOT NULL,
        entity_version NUMBER(19) DEFAULT 0,
        deleted VARCHAR2(16)
    )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
@@

BEGIN
    EXECUTE IMMEDIATE 'CREATE TABLE sample_orders (
        order_id NUMBER(19) CONSTRAINT pk_sample_orders PRIMARY KEY,
        customer_id NUMBER(19) NOT NULL,
        order_date NUMBER(19) NOT NULL,
        order_amount NUMBER(19, 4) NOT NULL,
        currency_code VARCHAR2(8) NOT NULL,
        order_type VARCHAR2(32) NOT NULL,
        status VARCHAR2(24) NOT NULL,
        line_count NUMBER(10) NOT NULL,
        priority_score BINARY_DOUBLE NOT NULL,
        entity_version NUMBER(19) DEFAULT 0,
        deleted VARCHAR2(16),
        CONSTRAINT fk_sample_orders_customer FOREIGN KEY (customer_id)
            REFERENCES sample_customers(customer_id)
    )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
@@

BEGIN
    EXECUTE IMMEDIATE 'CREATE TABLE sample_order_lines (
        line_id NUMBER(19) CONSTRAINT pk_sample_order_lines PRIMARY KEY,
        order_id NUMBER(19) NOT NULL,
        product_id NUMBER(19) NOT NULL,
        line_number NUMBER(10) NOT NULL,
        sku VARCHAR2(64) NOT NULL,
        quantity NUMBER(10) NOT NULL,
        unit_price NUMBER(19, 4) NOT NULL,
        line_total NUMBER(19, 4) NOT NULL,
        status VARCHAR2(24) NOT NULL,
        entity_version NUMBER(19) DEFAULT 0,
        deleted VARCHAR2(16),
        CONSTRAINT fk_sample_order_lines_order FOREIGN KEY (order_id)
            REFERENCES sample_orders(order_id),
        CONSTRAINT fk_sample_order_lines_product FOREIGN KEY (product_id)
            REFERENCES sample_products(product_id)
    )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
@@

BEGIN
    EXECUTE IMMEDIATE 'CREATE TABLE sample_support_tickets (
        ticket_id NUMBER(19) CONSTRAINT pk_sample_support_tickets PRIMARY KEY,
        customer_id NUMBER(19) NOT NULL,
        priority VARCHAR2(16) NOT NULL,
        status VARCHAR2(24) NOT NULL,
        subject VARCHAR2(180) NOT NULL,
        opened_at NUMBER(19) NOT NULL,
        updated_at NUMBER(19) NOT NULL,
        entity_version NUMBER(19) DEFAULT 0,
        deleted VARCHAR2(16),
        CONSTRAINT fk_sample_tickets_customer FOREIGN KEY (customer_id)
            REFERENCES sample_customers(customer_id)
    )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
@@

BEGIN
    EXECUTE IMMEDIATE 'CREATE TABLE sample_shipments (
        shipment_id NUMBER(19) CONSTRAINT pk_sample_shipments PRIMARY KEY,
        customer_id NUMBER(19) NOT NULL,
        tracking_number VARCHAR2(80) CONSTRAINT uq_sample_shipments_tracking UNIQUE NOT NULL,
        carrier_code VARCHAR2(24) NOT NULL,
        shipment_status VARCHAR2(32) NOT NULL,
        current_city VARCHAR2(80) NOT NULL,
        promised_at NUMBER(19) NOT NULL,
        updated_at NUMBER(19) NOT NULL,
        risk_score BINARY_DOUBLE NOT NULL,
        entity_version NUMBER(19) DEFAULT 0,
        deleted VARCHAR2(16),
        CONSTRAINT fk_sample_shipments_customer FOREIGN KEY (customer_id)
            REFERENCES sample_customers(customer_id)
    )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
@@

BEGIN
    EXECUTE IMMEDIATE 'CREATE TABLE sample_shipment_events (
        event_id NUMBER(19) CONSTRAINT pk_sample_shipment_events PRIMARY KEY,
        shipment_id NUMBER(19) NOT NULL,
        event_type VARCHAR2(40) NOT NULL,
        event_city VARCHAR2(80) NOT NULL,
        event_time NUMBER(19) NOT NULL,
        severity VARCHAR2(16) NOT NULL,
        description VARCHAR2(240) NOT NULL,
        entity_version NUMBER(19) DEFAULT 0,
        deleted VARCHAR2(16),
        CONSTRAINT fk_sample_shipment_events_shipment FOREIGN KEY (shipment_id)
            REFERENCES sample_shipments(shipment_id)
    )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
@@

BEGIN
    EXECUTE IMMEDIATE 'CREATE TABLE sample_report_jobs (
        report_job_id NUMBER(19) CONSTRAINT pk_sample_report_jobs PRIMARY KEY,
        report_type VARCHAR2(40) NOT NULL,
        status VARCHAR2(24) NOT NULL,
        requested_by VARCHAR2(120) NOT NULL,
        created_at NUMBER(19) NOT NULL,
        updated_at NUMBER(19) NOT NULL,
        row_count NUMBER(10) NOT NULL,
        failure_reason VARCHAR2(240),
        entity_version NUMBER(19) DEFAULT 0,
        deleted VARCHAR2(16)
    )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
@@

BEGIN
    EXECUTE IMMEDIATE 'CREATE TABLE sample_audit_events (
        audit_event_id NUMBER(19) CONSTRAINT pk_sample_audit_events PRIMARY KEY,
        entity_name VARCHAR2(80) NOT NULL,
        entity_id NUMBER(19) NOT NULL,
        event_type VARCHAR2(40) NOT NULL,
        severity VARCHAR2(16) NOT NULL,
        actor VARCHAR2(120) NOT NULL,
        created_at NUMBER(19) NOT NULL,
        message VARCHAR2(240) NOT NULL,
        entity_version NUMBER(19) DEFAULT 0,
        deleted VARCHAR2(16)
    )';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
@@

BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_orders_customer_date ON sample_orders(customer_id, order_date DESC, order_id DESC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_orders_priority ON sample_orders(priority_score DESC, order_date DESC, order_id DESC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_order_lines_order_number ON sample_order_lines(order_id, line_number ASC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_customers_active ON sample_customers(status, updated_at DESC, customer_id ASC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_tickets_customer_status ON sample_support_tickets(customer_id, status)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_tickets_status_priority ON sample_support_tickets(status, priority, updated_at DESC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_tickets_open_updated ON sample_support_tickets(status, updated_at DESC, ticket_id ASC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_products_category_stock ON sample_products(category, active_status, stock_status, updated_at DESC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_products_low_stock ON sample_products(active_status, stock_status, updated_at DESC, sku ASC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_shipments_active ON sample_shipments(shipment_status, risk_score DESC, updated_at DESC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_shipments_customer_updated ON sample_shipments(customer_id, updated_at DESC, shipment_id DESC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_shipment_events_shipment_time ON sample_shipment_events(shipment_id, event_time DESC, event_id DESC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_report_jobs_live ON sample_report_jobs(status, updated_at DESC, report_job_id DESC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_report_jobs_type_created ON sample_report_jobs(report_type, created_at DESC, report_job_id DESC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_audit_events_entity_time ON sample_audit_events(entity_name, entity_id, created_at DESC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@
BEGIN EXECUTE IMMEDIATE 'CREATE INDEX idx_sample_audit_events_security ON sample_audit_events(severity, created_at DESC)';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF; END;
@@

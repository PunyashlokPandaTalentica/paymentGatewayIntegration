-- Create orders table
CREATE TABLE orders (
    id UUID PRIMARY KEY,
    merchant_order_id VARCHAR(100) UNIQUE NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    description TEXT,
    customer_email VARCHAR(255),
    customer_phone VARCHAR(50),
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_orders_merchant_id ON orders(merchant_order_id);

-- Create payments table
CREATE TABLE payments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    payment_type VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    gateway VARCHAR(30) NOT NULL,
    idempotency_key VARCHAR(100) UNIQUE NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_payments_order_id ON payments(order_id);
CREATE UNIQUE INDEX idx_payment_idempotency ON payments(idempotency_key);

-- Create payment_transactions table (IMMUTABLE)
CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    transaction_state VARCHAR(30) NOT NULL,
    gateway_transaction_id VARCHAR(100),
    gateway_response_code VARCHAR(50),
    gateway_response_msg TEXT,
    amount_cents BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    parent_transaction_id UUID,
    trace_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_tx_payment_id ON payment_transactions(payment_id);
CREATE INDEX idx_tx_gateway_id ON payment_transactions(gateway_transaction_id);

-- Create webhooks table
CREATE TABLE webhooks (
    id UUID PRIMARY KEY,
    gateway VARCHAR(30) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    gateway_event_id VARCHAR(100) UNIQUE NOT NULL,
    payload JSONB NOT NULL,
    signature_verified BOOLEAN NOT NULL,
    processed BOOLEAN NOT NULL,
    processed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_webhook_event ON webhooks(gateway_event_id);

-- Create payment_attempts table (optional but useful)
CREATE TABLE payment_attempts (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    attempt_no INT NOT NULL,
    reason TEXT,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_attempts_payment_id ON payment_attempts(payment_id);


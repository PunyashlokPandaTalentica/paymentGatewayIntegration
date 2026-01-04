-- Create subscriptions table
CREATE TABLE subscriptions (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    merchant_subscription_id VARCHAR(100) UNIQUE NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    recurrence_interval VARCHAR(20) NOT NULL,
    interval_count INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(30) NOT NULL,
    gateway VARCHAR(30) NOT NULL,
    payment_method_token VARCHAR(255) NOT NULL,
    start_date TIMESTAMP NOT NULL,
    next_billing_date TIMESTAMP NOT NULL,
    end_date TIMESTAMP,
    max_billing_cycles INTEGER,
    current_billing_cycle INTEGER DEFAULT 0,
    description TEXT,
    idempotency_key VARCHAR(100) UNIQUE NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_subscriptions_customer_id ON subscriptions(customer_id);
CREATE INDEX idx_subscriptions_merchant_id ON subscriptions(merchant_subscription_id);
CREATE UNIQUE INDEX idx_subscription_idempotency ON subscriptions(idempotency_key);
CREATE INDEX idx_subscriptions_next_billing_date ON subscriptions(next_billing_date);
CREATE INDEX idx_subscriptions_status ON subscriptions(status);

-- Create subscription_payments table to track payments made for each billing cycle
CREATE TABLE subscription_payments (
    id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    order_id UUID NOT NULL,
    billing_cycle INTEGER NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    scheduled_date TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_subscription_payment_subscription FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE,
    CONSTRAINT fk_subscription_payment_payment FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_payment_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT
);

CREATE INDEX idx_subscription_payments_subscription_id ON subscription_payments(subscription_id);
CREATE INDEX idx_subscription_payments_payment_id ON subscription_payments(payment_id);
CREATE INDEX idx_subscription_payments_order_id ON subscription_payments(order_id);
CREATE INDEX idx_subscription_payments_billing_cycle ON subscription_payments(subscription_id, billing_cycle);

-- Add comment to tables
COMMENT ON TABLE subscriptions IS 'Stores subscription information for recurring payments';
COMMENT ON TABLE subscription_payments IS 'Links subscription billing cycles to payments and orders';


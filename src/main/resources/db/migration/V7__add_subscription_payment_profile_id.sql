ALTER TABLE subscriptions
    ADD COLUMN payment_profile_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_subscriptions_payment_profile_id
    ON subscriptions (payment_profile_id);

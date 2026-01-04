-- Replace payment_method_token with customer_profile_id for subscriptions
-- This migration supports Authorize.Net Customer Profiles for recurring payments
-- Customer Profiles don't expire like Accept.js tokens (which expire in 15 minutes)

-- Add new column (nullable initially to handle existing data)
ALTER TABLE subscriptions ADD COLUMN customer_profile_id VARCHAR(50);

-- Remove old column
ALTER TABLE subscriptions DROP COLUMN payment_method_token;

-- Make customer_profile_id NOT NULL (new subscriptions will always have this)
-- Note: If you have existing subscriptions, you'll need to migrate them separately
-- by creating Customer Profiles from the old tokens before running this migration
ALTER TABLE subscriptions ALTER COLUMN customer_profile_id SET NOT NULL;

-- Add index for customer_profile_id lookups
CREATE INDEX idx_subscriptions_customer_profile_id ON subscriptions(customer_profile_id);


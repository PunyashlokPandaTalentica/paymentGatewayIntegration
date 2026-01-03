-- Create failed_webhooks table for dead letter queue
CREATE TABLE IF NOT EXISTS failed_webhooks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_id UUID NOT NULL,
    gateway_event_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    error_message TEXT,
    error_stack_trace TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_retry_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_failed_webhook_webhook FOREIGN KEY (webhook_id) REFERENCES webhooks(id) ON DELETE CASCADE
);

-- Create indexes for efficient querying
CREATE INDEX idx_failed_webhooks_webhook_id ON failed_webhooks(webhook_id);
CREATE INDEX idx_failed_webhooks_gateway_event_id ON failed_webhooks(gateway_event_id);
CREATE INDEX idx_failed_webhooks_created_at ON failed_webhooks(created_at);
CREATE INDEX idx_failed_webhooks_retry_count ON failed_webhooks(retry_count);

-- Add comment to table
COMMENT ON TABLE failed_webhooks IS 'Dead letter queue for webhooks that failed to process after retries';


# System Architecture

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        API Layer (REST)                          │
│  Orders | Payments | Transactions | Subscriptions | Webhooks    │
└────────────────────────┬────────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────────┐
│                  Business Logic Layer                            │
│  PaymentOrchestrator | WebhookProcessor | Subscription Service  │
└────────────────────────┬────────────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         │               │               │
    ┌────▼────┐    ┌─────▼─────┐    ┌───▼──────┐
    │  Domain │    │  Gateway  │    │Persistence
    │ Models  │    │Integration│    │  (JPA)
    │& State  │    │           │    │
    │Machine  │    │Authorize. │    │ PostgreSQL
    │         │    │   Net     │    │
    └─────────┘    └───────────┘    └──────────┘
```

## API Endpoints

### Orders API

| Method | Endpoint               | Description            | Status      |
| ------ | ---------------------- | ---------------------- | ----------- |
| POST   | `/v1/orders`           | Create new order       | 201 Created |
| GET    | `/v1/orders/{orderId}` | Retrieve order details | 200 OK      |

**Create Order Request:**

```json
{
  "merchantOrderId": "ORD-12345",
  "amount": {
    "value": 99.99,
    "currency": "USD"
  },
  "description": "Premium subscription",
  "customer": {
    "email": "customer@example.com",
    "phone": "+1234567890"
  }
}
```

**Order Response:**

```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "merchantOrderId": "ORD-12345",
  "amount": {
    "value": 99.99,
    "currency": "USD"
  },
  "status": "CREATED",
  "createdAt": "2024-01-01T10:00:00Z"
}
```

### Payments API

| Method | Endpoint                        | Description      | Status      | Headers                    |
| ------ | ------------------------------- | ---------------- | ----------- | -------------------------- |
| POST   | `/v1/orders/{orderId}/payments` | Create payment   | 201 Created | Idempotency-Key (optional) |
| GET    | `/v1/payments/{paymentId}`      | Retrieve payment | 200 OK      | -                          |

**Create Payment Request:**

```json
{
  "flow": "PURCHASE",
  "gateway": "AUTHORIZE_NET",
  "paymentMethodToken": "tok_visa_4242"
}
```

**Payment Response:**

```json
{
  "paymentId": "660e8400-e29b-41d4-a716-446655440111",
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "INITIATED",
  "flow": "PURCHASE"
}
```

**Idempotency Support:**

```bash
# Request with idempotency key
curl -X POST /v1/orders/{orderId}/payments \
  -H "Idempotency-Key: unique-key-12345" \
  -H "Content-Type: application/json" \
  -d '{...}'

# Same request with same key returns same response (idempotent)
```

### Transactions API

| Method | Endpoint                                                        | Description                       | Status      |
| ------ | --------------------------------------------------------------- | --------------------------------- | ----------- |
| POST   | `/v1/payments/{paymentId}/transactions/purchase`                | Process purchase (auth + capture) | 201 Created |
| POST   | `/v1/payments/{paymentId}/transactions/authorize`               | Authorize only                    | 201 Created |
| POST   | `/v1/payments/{paymentId}/transactions/{transactionId}/capture` | Capture authorized                | 201 Created |
| GET    | `/v1/payments/{paymentId}/transactions`                         | List all transactions             | 200 OK      |

**Purchase/Authorize Request:**

```json
{
  "amount": {
    "value": 99.99,
    "currency": "USD"
  }
}
```

**Transaction Response:**

```json
{
  "transactionId": "770e8400-e29b-41d4-a716-446655440222",
  "paymentId": "660e8400-e29b-41d4-a716-446655440111",
  "transactionType": "PURCHASE",
  "state": "SETTLED",
  "authorizationCode": "A123BC",
  "transactionRefId": "60123456789",
  "createdAt": "2024-01-01T10:05:00Z"
}
```

### Subscriptions API

| Method | Endpoint                                             | Description              | Status      |
| ------ | ---------------------------------------------------- | ------------------------ | ----------- |
| POST   | `/v1/subscriptions`                                  | Create subscription      | 201 Created |
| GET    | `/v1/subscriptions/{subscriptionId}`                 | Get subscription details | 200 OK      |
| POST   | `/v1/subscriptions/{subscriptionId}/cancel`          | Cancel subscription      | 200 OK      |
| POST   | `/v1/subscriptions/{subscriptionId}/billing/trigger` | Trigger billing manually | 200 OK      |

**Create Subscription Request:**

```json
{
  "customerId": "customer-12345",
  "merchantSubscriptionId": "SUB-99999",
  "amount": {
    "value": 29.99,
    "currency": "USD"
  },
  "interval": "MONTHLY",
  "intervalCount": 1,
  "paymentMethodToken": "tok_visa_4242",
  "gateway": "AUTHORIZE_NET",
  "description": "Premium plan",
  "idempotencyKey": "sub-key-unique",
  "startDate": "2024-02-01T00:00:00Z",
  "maxBillingCycles": 12
}
```

**Subscription Response:**

```json
{
  "subscriptionId": "880e8400-e29b-41d4-a716-446655440333",
  "customerId": "customer-12345",
  "merchantSubscriptionId": "SUB-99999",
  "amount": {
    "value": 29.99,
    "currency": "USD"
  },
  "interval": "MONTHLY",
  "status": "ACTIVE",
  "nextBillingDate": "2024-02-01T00:00:00Z",
  "createdAt": "2024-01-01T10:10:00Z"
}
```

### Webhooks API

| Method | Endpoint                     | Description                    |
| ------ | ---------------------------- | ------------------------------ |
| POST   | `/v1/webhooks/authorize-net` | Receive Authorize.Net webhooks |

**Webhook Processing:**

- Signature validation (HMAC-SHA512)
- Duplicate detection by gateway_event_id
- Asynchronous processing
- Automatic retry on failure
- Dead letter queue for manual handling

## Payment Processing Flows

### Purchase Flow (Auth + Capture in One Step)

```
Customer
    ↓
[POST /v1/orders] → Create Order
    ↓
[POST /v1/orders/{orderId}/payments] → Create Payment (with idempotencyKey)
    ↓ (Payment state: INITIATED)
[POST /v1/payments/{paymentId}/transactions/purchase]
    ↓
Authorize.Net Gateway
    ↓
[Response: AuthorizationCode + TransactionRef]
    ↓ (Payment state: CAPTURED → SETTLED via webhook)
Customer Charged ✓
```

### Authorization + Capture Flow

```
Customer
    ↓
[POST /v1/orders] → Create Order
    ↓
[POST /v1/orders/{orderId}/payments] → Create Payment
    ↓ (Payment state: INITIATED)
[POST /v1/payments/{paymentId}/transactions/authorize]
    ↓
Authorize.Net Gateway
    ↓ (Payment state: AUTHORIZED via webhook)
[Wait for Fulfillment]
    ↓
[POST /v1/payments/{paymentId}/transactions/{transactionId}/capture]
    ↓
Authorize.Net Gateway
    ↓ (Payment state: CAPTURED → SETTLED via webhook)
Customer Charged ✓
```

### Subscription Billing Flow

```
[Create Subscription]
    ↓
[ACTIVE Status]
    ↓
[Next Billing Date Reached]
    ↓
[Automated Billing Job]
    ↓
[Create Recurring Charge]
    ↓
[Authorize.Net Gateway]
    ↓ (Success)
[Update Subscription: next_billing_date += interval]
    ↓ (Failure)
[Retry with Exponential Backoff]
    ↓
[Max Retries Exceeded] → [Cancel Subscription]
```

### Webhook Processing Flow

```
Authorize.Net Webhook Event
    ↓
[POST /v1/webhooks/authorize-net]
    ↓
[Validate Signature] (HMAC-SHA512)
    ↓ (Invalid) → 400 Bad Request
    ↓ (Valid)
[Check for Duplicate] (by gateway_event_id)
    ↓ (Duplicate) → Ignore
    ↓ (New Event)
[Async Processing] (@Async)
    ↓
[Update Payment State]
    ↓ (Success) → 200 OK
    ↓ (Failure) → Dead Letter Queue
```

## Database Schema

### Entity Relationships

```
Orders (1)
  ↓
  └──── (1) Payments
        ↓
        └──── (*) PaymentTransactions

Subscriptions (1)
  ↓
  └──── (*) SubscriptionPayments → PaymentTransactions

WebhookEvents (stand-alone, processed asynchronously)

PaymentAttempts (tracking for retries)
```

### Key Tables

#### orders

```sql
CREATE TABLE orders (
  id UUID PRIMARY KEY,
  merchant_order_id VARCHAR(100) UNIQUE NOT NULL,
  amount_cents BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  status VARCHAR(30) NOT NULL,
  customer_id VARCHAR(255),
  customer_email VARCHAR(255),
  customer_phone VARCHAR(20),
  description TEXT,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
INDEX idx_orders_merchant_id ON orders(merchant_order_id);
```

#### payments

```sql
CREATE TABLE payments (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(id),
  status VARCHAR(30) NOT NULL,
  payment_type VARCHAR(30) NOT NULL,
  gateway VARCHAR(50) NOT NULL,
  idempotency_key VARCHAR(255) UNIQUE,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
UNIQUE INDEX idx_payment_idempotency ON payments(idempotency_key);
INDEX idx_payments_order_id ON payments(order_id);
```

#### payment_transactions (Immutable, Append-Only)

```sql
CREATE TABLE payment_transactions (
  id UUID PRIMARY KEY,
  payment_id UUID NOT NULL REFERENCES payments(id),
  transaction_type VARCHAR(30) NOT NULL,
  state VARCHAR(30) NOT NULL,
  amount_cents BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  authorization_code VARCHAR(255),
  transaction_ref_id VARCHAR(255),
  parent_transaction_id UUID,
  gateway_response JSONB,
  created_at TIMESTAMP NOT NULL
);
INDEX idx_tx_payment_id ON payment_transactions(payment_id);
```

#### webhooks

```sql
CREATE TABLE webhooks (
  id UUID PRIMARY KEY,
  gateway VARCHAR(50) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  gateway_event_id VARCHAR(255) UNIQUE NOT NULL,
  payload JSONB NOT NULL,
  signature VARCHAR(255),
  processed BOOLEAN DEFAULT false,
  created_at TIMESTAMP NOT NULL
);
UNIQUE INDEX idx_webhook_event ON webhooks(gateway_event_id);
```

#### subscriptions

```sql
CREATE TABLE subscriptions (
  id UUID PRIMARY KEY,
  customer_id VARCHAR(255) NOT NULL,
  merchant_subscription_id VARCHAR(255) UNIQUE NOT NULL,
  amount_cents BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  interval VARCHAR(20) NOT NULL,
  interval_count INT DEFAULT 1,
  status VARCHAR(30) NOT NULL,
  next_billing_date TIMESTAMP NOT NULL,
  max_billing_cycles INT,
  current_billing_cycle INT DEFAULT 0,
  idempotency_key VARCHAR(255) UNIQUE,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
INDEX idx_subscriptions_customer_id ON subscriptions(customer_id);
INDEX idx_subscriptions_next_billing ON subscriptions(next_billing_date);
```

## Payment State Machine

```
State Transitions Based on Transactions:

INITIATED
  ↓ (POST /authorize or /purchase)
AUTHORIZED
  ↓ (Capture succeeds or Auto-captured in purchase)
CAPTURED
  ↓ (Settlement webhook received)
SETTLED ✓ (Final state)

OR

DECLINED (if authorization/purchase fails)
FAILED (if capture/settlement fails)
```

**State Derivation Logic:**

```
if (no transactions) → INITIATED
if (any DECLINED transaction) → DECLINED
if (any FAILED transaction) → FAILED
if (latest state is SUBMITTED) → INITIATED
if (latest state is AUTHORIZED) → AUTHORIZED
if (latest state is SETTLED) → CAPTURED (or SETTLED)
```

## Design Trade-offs

### Synchronous vs Asynchronous

| Operation              | Mode                  | Reason                                          |
| ---------------------- | --------------------- | ----------------------------------------------- |
| Order Creation         | **Sync**              | Instant validation, immediate ID return         |
| Payment Creation       | **Sync**              | Immediate status, support idempotency check     |
| Transaction Processing | **Sync**              | Customer feedback, transaction ID tracking      |
| Webhook Processing     | **Async**             | High throughput, retry capability, non-blocking |
| Subscription Billing   | **Async (Scheduled)** | Off-peak processing, batch operations           |

### Retry Strategies

| Component            | Strategy                | Config                         |
| -------------------- | ----------------------- | ------------------------------ |
| Gateway Calls        | **Exponential Backoff** | 3 retries, 1s → 2s → 4s        |
| Webhook Processing   | **Exponential Backoff** | 3 retries, async task executor |
| Subscription Billing | **Linear Backoff**      | 3 retries, 24h intervals       |
| Failed Webhooks      | **Dead Letter Queue**   | Manual retry capability        |

### Data Consistency

| Concern                  | Solution                                |
| ------------------------ | --------------------------------------- |
| Duplicate Payments       | Idempotency key (unique index)          |
| Duplicate Webhooks       | gateway_event_id (unique index)         |
| Order/Payment Mismatch   | Foreign key constraint                  |
| Transaction Immutability | No UPDATE on payment_transactions       |
| Payment State Conflicts  | Pessimistic locking (SELECT FOR UPDATE) |

### Caching Strategy

| Component           | Strategy                                  |
| ------------------- | ----------------------------------------- |
| Order Lookup        | Database query (low volume)               |
| Payment Lookup      | Database query (idempotency check cached) |
| Webhook Dedup       | Database unique index (fast)              |
| Authorization Codes | Database JSONB (no separate cache)        |

## Compliance Considerations

### PCI DSS Compliance

- ✅ **No Credit Card Storage**: Payment method tokens used instead
- ✅ **Encrypted Transmission**: HTTPS/TLS required
- ✅ **Audit Logging**: Complete transaction ledger
- ✅ **Access Control**: OAuth2 JWT authentication
- ✅ **Data Minimization**: Only necessary data retained

### Payment Processing Standards

- ✅ **EMV Compliance**: Delegated to Authorize.Net
- ✅ **3D Secure**: Supported via gateway
- ✅ **GDPR Compliance**: Customer data handling, no unnecessary storage
- ✅ **PII Protection**: Sanitized inputs, no logs of sensitive data
- ✅ **Signature Validation**: HMAC-SHA512 for webhooks

### API Security

- ✅ **OAuth2 JWT**: Auth0 integration for authentication
- ✅ **Input Validation**: RequestSanitizationService
- ✅ **UUID Format Validation**: Prevents injection
- ✅ **Rate Limiting**: Infrastructure-level (WAF)
- ✅ **Error Handling**: Generic error messages (no stack traces to clients)

### Data Privacy

| Data                 | Treatment                                  |
| -------------------- | ------------------------------------------ |
| Payment Method Token | Gateway-managed, never stored as-is        |
| Authorization Code   | Stored for reference, never logged         |
| Customer Email/Phone | Encrypted in database, minimal retention   |
| Transaction Details  | Complete audit trail, 7-year retention     |
| Webhook Payloads     | Stored as JSONB for debugging (PII masked) |

## Performance Considerations

### Query Optimization

```sql
-- Fast lookup by merchant order ID
SELECT * FROM orders WHERE merchant_order_id = ?;
-- INDEX idx_orders_merchant_id

-- Fast transaction list retrieval
SELECT * FROM payment_transactions WHERE payment_id = ? ORDER BY created_at;
-- INDEX idx_tx_payment_id

-- Webhook deduplication (unique constraint)
SELECT * FROM webhooks WHERE gateway_event_id = ?;
-- UNIQUE INDEX idx_webhook_event
```

### Connection Pooling

- **HikariCP** (Spring Boot default)
- Pool size: 10 connections
- Max lifetime: 30 minutes
- Idle timeout: 10 minutes

### Async Processing

- **Core threads**: 5
- **Max threads**: 10
- **Queue capacity**: 100
- Webhook processing, subscription billing off-loaded

## Scalability

### Horizontal Scaling

- Stateless application (all state in database)
- Load balancer friendly
- Database as single source of truth

### Database Optimization

- Connection pooling
- Indexed queries
- Immutable transactions (no locks)
- Pessimistic locking only on payment updates

### Potential Bottlenecks & Solutions

| Bottleneck           | Solution                                  |
| -------------------- | ----------------------------------------- |
| High webhook volume  | Increase async thread pool, message queue |
| Payment state locks  | Optimistic locking (version field)        |
| Order lookup         | Caching layer (Redis)                     |
| Subscription billing | Scheduled batching, worker pools          |

---

## Deployment Architecture

```
┌──────────────────┐
│   Auth0          │ (OAuth2 Provider)
└────────┬─────────┘
         │
┌────────▼─────────────────────────┐
│  Load Balancer (HTTPS)           │
└────────┬────────────────┬────────┘
         │                │
┌────────▼──────┐ ┌─────▼──────────┐
│ App Instance 1│ │ App Instance 2  │
└────────┬──────┘ └─────┬──────────┘
         │                │
         └────────┬───────┘
                  │
        ┌─────────▼──────────┐
        │  PostgreSQL (HA)   │
        │  Primary + Replica │
        └────────────────────┘
```

---

**Last Updated**: January 2026

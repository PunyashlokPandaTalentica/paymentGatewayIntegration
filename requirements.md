Payment Processing System

Initial Architecture & Requirements Document (v0.2)

1. Purpose & Goals

Build a single-tenant payment orchestration service that:

Directly integrates with Authorize.Net Java SDK

Exposes stable, declarative REST APIs

Implements an explicit payment state machine

Treats Authorize.Net webhooks as authoritative

Guarantees idempotency, immutability, and thread safety

Supports:

Purchase (Auth + Capture)

Authorize → Capture (2-step)

Uses in-memory persistence initially, but remains production-parity

2. Explicit Non-Goals (v1)

Multi-tenant support

Multiple payment gateways

Refunds, voids, partial capture

Advanced subscription features (basic recurring only)

UI / Admin console

3. High-Level Architecture
Merchant / Client
      |
      | REST
      v
Spring Boot Payment API
      |
      | Command Handling
      v
Payment Orchestrator
      |
      | State Validation + Transition
      v
Payment State Machine
      |
      | Gateway Calls (REAL)
      v
Authorize.Net Java SDK
      |
      | Webhooks (Authoritative)
      v
Webhook Ingress → Validation → Queue → Processor

4. Core Architecture Principles

Authorize.Net SDK is used in runtime

Mocks are allowed ONLY in tests

One Order → One Payment → One Transaction

Transactions are immutable

State transitions are explicit and forward-only

Webhooks may advance state

Every write API is idempotent

All commits are thread-safe

5. Gateway Integration Strategy
5.1 Authorize.Net SDK Usage

Use official Authorize.Net Java SDK

SDK is invoked from a Gateway Adapter layer

SDK configuration (keys, env) via Spring config

Sandbox mode for development

Production credentials injected at runtime

5.2 Gateway Adapter Interface
interface PaymentGateway {
    AuthResponse authorize(PaymentRequest request);
    CaptureResponse capture(String transactionId);
    PurchaseResponse purchase(PaymentRequest request);
}


Authorize.Net implementation is mandatory

Future gateway abstraction is deferred

6. Mocking Policy (Very Important)
Allowed

✅ Unit tests
✅ Integration tests
✅ Failure simulation
✅ Webhook replay testing

==========================================


Proposed DB: 

Below is a clean, production-oriented DB schema proposal aligned with what you’ve already decided:

One order → one payment → one transaction

Transactions immutable

Supports Purchase (Auth+Capture) and Auth/Capture

Idempotent, webhook-safe

In-memory now, RDBMS-ready (Postgres)

Authorize.Net–centric but gateway-agnostic later

1. Core Principles Applied

Immutability:
transactions are append-only. State changes create new rows, not updates.

Separation of concerns:

order = business intent

payment = payment lifecycle

transaction = gateway interaction (authoritative)

Idempotency everywhere

Webhook = authoritative input

2. Tables Overview
orders
payments
payment_transactions
payment_attempts
webhooks
refunds

3. Schema (DDL-style)
3.1 orders
orders (
  id                  UUID PK,
  merchant_order_id   VARCHAR(100) UNIQUE NOT NULL,
  amount_cents        BIGINT NOT NULL,
  currency            CHAR(3) NOT NULL,

  status              VARCHAR(30) NOT NULL,
  -- CREATED | PAYMENT_PENDING | PAID | FAILED | CANCELLED

  created_at          TIMESTAMP NOT NULL,
  updated_at          TIMESTAMP NOT NULL
)


Purpose

Represents business intent

Status is derived from payment state

3.2 payments
payments (
  id                  UUID PK,
  order_id            UUID FK -> orders(id),

  payment_type        VARCHAR(20) NOT NULL,
  -- PURCHASE | AUTH_CAPTURE

  status              VARCHAR(30) NOT NULL,
  -- INITIATED | AUTHORIZED | CAPTURED | FAILED | CANCELLED

  amount_cents        BIGINT NOT NULL,
  currency            CHAR(3) NOT NULL,

  gateway             VARCHAR(30) NOT NULL,
  -- AUTHORIZE_NET

  idempotency_key     VARCHAR(100) UNIQUE NOT NULL,

  created_at          TIMESTAMP NOT NULL,
  updated_at          TIMESTAMP NOT NULL
)


Notes

One payment per order (enforced at service level)

Idempotency is per business intent

3.3 payment_transactions (IMMUTABLE)
payment_transactions (
  id                  UUID PK,
  payment_id          UUID FK -> payments(id),

  transaction_type    VARCHAR(20) NOT NULL,
  -- AUTH | CAPTURE | PURCHASE | VOID | REFUND

  transaction_state   VARCHAR(30) NOT NULL,
  -- REQUESTED | SUCCESS | FAILED | PENDING

  gateway_transaction_id VARCHAR(100),
  gateway_response_code  VARCHAR(50),
  gateway_response_msg   TEXT,

  amount_cents        BIGINT NOT NULL,
  currency            CHAR(3) NOT NULL,

  parent_transaction_id UUID NULL,
  -- capture/refund references auth/purchase

  trace_id            UUID NOT NULL,
  -- regenerated on retry, references original via parent_transaction_id

  created_at          TIMESTAMP NOT NULL
)


Critical Rules

❌ No UPDATEs

✔ Every retry = new row

✔ Gateway is source of truth

3.4 payment_attempts (optional but useful)
payment_attempts (
  id                  UUID PK,
  payment_id          UUID FK -> payments(id),

  attempt_no          INT NOT NULL,
  reason              TEXT,

  created_at          TIMESTAMP NOT NULL
)


Tracks soft retries / client retries cleanly.

3.5 webhooks (idempotent & auditable)
webhooks (
  id                  UUID PK,

  gateway             VARCHAR(30) NOT NULL,
  event_type          VARCHAR(50) NOT NULL,
  gateway_event_id    VARCHAR(100) UNIQUE NOT NULL,

  payload             JSONB NOT NULL,
  signature_verified  BOOLEAN NOT NULL,

  processed           BOOLEAN NOT NULL,
  processed_at        TIMESTAMP,

  created_at          TIMESTAMP NOT NULL
)


Why separate table

Deduplication

Replay

Audit & debugging

Queue-safe processing

3.6 refunds
refunds (
  id                  UUID PK,
  payment_id          UUID FK -> payments(id),
  transaction_id      UUID FK -> payment_transactions(id),

  amount_cents        BIGINT NOT NULL,
  reason              TEXT,

  status              VARCHAR(30) NOT NULL,
  -- REQUESTED | SUCCESS | FAILED

  created_at          TIMESTAMP NOT NULL
)


Refunds are modeled explicitly but executed via transactions.

4. State Derivation (IMPORTANT)
Entity	State is…
orders.status	Derived from payments.status
payments.status	Derived from latest successful transaction
transactions	Authoritative

Never infer state from request success. Only from transaction rows.

5. Idempotency Strategy
Level	Key
API	idempotency_key
Gateway	gateway transaction ID
Webhook	gateway_event_id
Retry	new trace_id, reference original
6. Thread Safety Rules

payments updates via SELECT … FOR UPDATE

payment_transactions → insert-only

Webhook processor must:

Verify signature

Check gateway_event_id

Insert transaction

Update payment state

7. Minimal Indexes (Must-Have)
CREATE UNIQUE INDEX idx_payment_idempotency ON payments(idempotency_key);
CREATE INDEX idx_tx_payment_id ON payment_transactions(payment_id);
CREATE UNIQUE INDEX idx_webhook_event ON webhooks(gateway_event_id);

8. Why This Schema Works for Your Plan

✔ Supports Authorize.Net Purchase & Auth/Capture
✔ Safe retries & replayable webhooks
✔ Gateway-agnostic later
✔ Easy transition from in-memory → Postgres
✔ Clear audit trail (PCI-friendly)




==============================================================================================================


Below is a precise, implementation-ready REST contract for the Payment Processing system, aligned with your earlier constraints:

Immutable transactions

One order → one payment → one transaction

Auth+Capture (single step) OR Auth then Capture (two-step)

Idempotent, thread-safe

Authoritative webhooks

Retry soft failures

New trace per retry, reference original transaction

In-memory for now

Authorize.Net as gateway (SDK mocked initially)

1. Core Resource Model
Resources
Order
Payment
Transaction
WebhookEvent


Relationships:

Order (1) ── (1) Payment ── (1..n) Transaction (immutable)

2. Common Conventions (Applies to ALL APIs)
Headers
Idempotency-Key: <uuid>          // mandatory for all POST /commit APIs
X-Request-Id: <uuid>             // generated if absent

Idempotency Rules

Same (Idempotency-Key + endpoint + body-hash) → same response

Conflicting payload → 409 IDENTITY_CONFLICT

Money Representation
{
  "amount": "1999.00",
  "currency": "INR"
}

Timestamps

RFC3339 UTC (2025-01-30T18:42:21Z)

3. Order APIs
3.1 Create Order
POST /v1/orders
Request
{
  "merchantOrderId": "ORD-12345",
  "amount": {
    "amount": "1999.00",
    "currency": "INR"
  },
  "description": "Pro subscription",
  "customer": {
    "email": "user@example.com",
    "phone": "+919999999999"
  }
}

Response 201
{
  "orderId": "ord_01HZX...",
  "status": "CREATED",
  "amount": {
    "amount": "1999.00",
    "currency": "INR"
  },
  "createdAt": "2025-01-30T18:42:21Z"
}

Order States
CREATED → PAYMENT_INITIATED → COMPLETED | FAILED

4. Payment APIs
4.1 Create Payment Intent
POST /v1/orders/{orderId}/payments
Request
{
  "method": "CARD",
  "flow": "AUTH_CAPTURE",   // AUTH_CAPTURE | AUTH_ONLY
  "gateway": "AUTHORIZE_NET"
}

Response 201
{
  "paymentId": "pay_01HZY...",
  "orderId": "ord_01HZX...",
  "status": "INITIATED",
  "flow": "AUTH_CAPTURE"
}

5. Transaction APIs (Immutable)
5.1 Commit Purchase (Auth + Capture)
POST /v1/payments/{paymentId}/transactions/purchase

Thread-safe, idempotent

Request
{
  "paymentMethodToken": "tok_visa_4242"
}

Response 201
{
  "transactionId": "txn_01HZ...",
  "type": "PURCHASE",
  "status": "SUCCESS",
  "amount": {
    "amount": "1999.00",
    "currency": "INR"
  },
  "gatewayReferenceId": "authnet_txn_9876",
  "createdAt": "2025-01-30T18:43:01Z"
}

5.2 Authorize Only
POST /v1/payments/{paymentId}/transactions/authorize
Response
{
  "transactionId": "txn_01HZ...",
  "type": "AUTHORIZE",
  "status": "AUTHORIZED",
  "authorizedAmount": {
    "amount": "1999.00",
    "currency": "INR"
  }
}

5.3 Capture Authorized Payment
POST /v1/payments/{paymentId}/transactions/{transactionId}/capture
Request
{
  "amount": {
    "amount": "1999.00",
    "currency": "INR"
  }
}

Response
{
  "transactionId": "txn_01HZC...",
  "parentTransactionId": "txn_01HZA...",
  "type": "CAPTURE",
  "status": "SUCCESS"
}

6. Retry Semantics (Soft Failures)
Rule

Retry creates a NEW transaction

References original transaction

Example
{
  "transactionId": "txn_retry_02",
  "type": "PURCHASE",
  "status": "SUCCESS",
  "retryOf": "txn_01HZ_failed"
}

7. Query APIs
7.1 Get Order
GET /v1/orders/{orderId}
{
  "orderId": "ord_01HZX...",
  "status": "COMPLETED",
  "payments": [
    {
      "paymentId": "pay_01HZY...",
      "status": "SUCCESS"
    }
  ]
}

7.2 Get Transactions for Payment
GET /v1/payments/{paymentId}/transactions
[
  {
    "transactionId": "txn_01HZ...",
    "type": "AUTHORIZE",
    "status": "AUTHORIZED"
  },
  {
    "transactionId": "txn_01HZC...",
    "type": "CAPTURE",
    "status": "SUCCESS"
  }
]

8. Webhook Contracts (Authoritative)
8.1 Webhook Payload
POST /v1/webhooks/authorize-net
{
  "eventId": "wh_01HZ...",
  "eventType": "TRANSACTION.SETTLED",
  "gateway": "AUTHORIZE_NET",
  "transactionReferenceId": "authnet_txn_9876",
  "occurredAt": "2025-01-30T18:45:00Z",
  "signature": "HMAC_SHA256(...)",
  "payload": {
    "status": "SETTLED",
    "amount": "1999.00"
  }
}

Processing Rules

Verify signature

Idempotent on eventId

Enqueue → async processor

Can transition internal state even if API failed earlier

9. Error Model (Unified)
{
  "error": {
    "code": "PAYMENT_DECLINED",
    "message": "Card was declined",
    "retryable": true,
    "traceId": "req_01HZ..."
  }
}

10. Status Enumerations
Transaction Status
INITIATED
AUTHORIZED
SUCCESS
FAILED
SETTLED
VOIDED

Payment Status
INITIATED
PARTIALLY_AUTHORIZED
AUTHORIZED
CAPTURED
FAILED

11. What This Design Guarantees

✔ Fully RESTful
✔ Business-intent idempotency
✔ Immutable transaction ledger
✔ Safe retries
✔ Gateway-authoritative reconciliation
✔ Spring Boot friendly
✔ Easy gateway abstraction later
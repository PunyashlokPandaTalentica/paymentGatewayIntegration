# Observability Strategy

## Overview

This document defines the metrics, logging, and tracing strategy for the Payment Orchestration Service. Observability enables production monitoring, debugging, and performance optimization.

## Metrics

### Application Metrics (Micrometer)

#### HTTP Request Metrics

```
http.server.requests
  - tags: method, status, uri
  - measures: count, total time, max time

Example:
  http_server_requests_seconds_count{method="POST",status="201",uri="/v1/orders"} 156
  http_server_requests_seconds_sum{method="POST",status="201",uri="/v1/orders"} 0.245
```

#### JPA/Hibernate Metrics

```
jpa.session.open_transactions
  - Count of open database transactions

jpa.sessions.open
  - Number of active JPA sessions

hibernate.sessions.open
  - Hibernate session count
```

#### Database Connection Metrics

```
db.postgresql.connections.open
  - Number of open database connections
  - Target: < 20 of max 30

db.postgresql.connections.idle
  - Idle connection count
```

#### Business Metrics

**Payments:**

```
payment.creation.total (Counter)
  - Total payments created
  - tags: gateway, payment_type, status

payment.processing.duration (Timer)
  - Time to process payment
  - tags: gateway, flow_type

payment.state.gauge (Gauge)
  - Current payment states distribution
  - tags: status (INITIATED, AUTHORIZED, CAPTURED, SETTLED)
```

**Transactions:**

```
transaction.total (Counter)
  - Total transactions processed
  - tags: type (PURCHASE, AUTHORIZE, CAPTURE), state

transaction.state.duration (Timer)
  - Time for state transitions
  - tags: from_state, to_state

transaction.failures (Counter)
  - Failed transactions
  - tags: reason (GATEWAY_ERROR, DECLINED, etc)
```

**Orders:**

```
order.creation.total (Counter)
  - Total orders created
  - tags: currency

order.status.gauge (Gauge)
  - Orders by status distribution
  - tags: status
```

**Webhooks:**

```
webhook.processing.total (Counter)
  - Total webhooks processed
  - tags: event_type, status (SUCCESS, FAILED, DUPLICATE)

webhook.processing.duration (Timer)
  - Time to process webhook
  - tags: event_type

webhook.failures (Counter)
  - Failed webhook processing
  - tags: reason
```

**Subscriptions:**

```
subscription.total (Counter)
  - Total subscriptions created
  - tags: interval

subscription.status.gauge (Gauge)
  - Subscriptions by status
  - tags: status (ACTIVE, PAUSED, CANCELLED)

subscription.billing.total (Counter)
  - Billing operations
  - tags: result (SUCCESS, FAILED)

subscription.billing.duration (Timer)
  - Time for billing cycle
  - tags: interval
```

#### JVM Metrics

```
jvm.memory.used
  - Memory in use
  - tags: area (heap, nonheap), id

jvm.memory.max
  - Max memory available

jvm.gc.memory.allocated
  - Memory allocated for garbage collection

jvm.threads.live
  - Currently active threads

jvm.threads.peak
  - Peak thread count

process.cpu.usage
  - CPU usage percentage

process.uptime.seconds
  - Application uptime
```

### Alerting Thresholds

| Metric                          | Threshold        | Severity |
| ------------------------------- | ---------------- | -------- |
| http_requests_errors (5xx)      | > 5% of requests | Critical |
| payment_processing_duration_p99 | > 5 seconds      | Warning  |
| transaction_failures            | > 3 in 5 minutes | Warning  |
| webhook_processing_failures     | > 10 in 1 hour   | Warning  |
| db_connection_pool              | > 25 of 30       | Critical |
| jvm_memory_used                 | > 80%            | Warning  |
| jvm_gc_pause                    | > 500ms          | Warning  |

## Logging Strategy

### Log Levels

```
DEBUG:
  - Method entry/exit
  - Variable values
  - Database query execution
  - Gateway request/response (sanitized)
  - Development only, NOT in production

INFO:
  - Application startup
  - Order creation
  - Payment initiation
  - Transaction processing
  - Webhook receipt
  - Configuration loaded

WARN:
  - Retry attempts
  - Duplicate idempotency keys
  - Webhook signature validation failure (first attempt)
  - Gateway timeout/slowness
  - Subscription billing failure (before retry)

ERROR:
  - Payment processing failure
  - Gateway errors (after retries exhausted)
  - Database errors
  - Webhook processing failure (after retries)
  - Unexpected exceptions
```

### Log Format

```json
{
  "timestamp": "2024-01-01T10:00:00.123Z",
  "level": "INFO",
  "logger": "com.paymentgateway.service.PaymentOrchestratorService",
  "message": "Creating payment for order",
  "thread": "http-nio-8080-exec-1",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "context": {
    "orderId": "550e8400-e29b-41d4-a716-446655440000",
    "paymentId": "660e8400-e29b-41d4-a716-446655440111",
    "gateway": "AUTHORIZE_NET",
    "status": "INITIATED"
  }
}
```

### Structured Logging Fields

```
Mandatory fields:
  - timestamp (ISO-8601)
  - level (DEBUG, INFO, WARN, ERROR)
  - logger (class name)
  - message (human-readable message)
  - thread (thread name)
  - traceId (request correlation ID)

Context fields (where applicable):
  - orderId, paymentId, transactionId
  - customerId, subscriptionId
  - gateway, paymentType, transactionType
  - status, state
  - retryCount, attemptNumber
  - durationMs (operation time)
  - errorCode, errorMessage
```

### Logging Components

#### PaymentOrchestratorService

```
✓ Order creation
✓ Payment creation
✓ Idempotency check results
✓ Transaction processing initiation
✓ State machine transitions
```

#### PaymentGateway (AuthorizeNetGateway)

```
✓ Gateway request sent (sanitized)
✓ Gateway response received (sanitized)
✓ Retry attempts
✓ Timeout/network errors
✓ Response parsing
```

#### WebhookProcessorService

```
✓ Webhook received
✓ Signature validation (success/failure)
✓ Duplicate detection
✓ Processing started (async)
✓ State update
✓ Retry attempts
✓ Dead letter queue storage
```

#### SubscriptionService

```
✓ Subscription creation
✓ Subscription cancellation
✓ Billing cycle triggered
✓ Recurring charge processed
✓ Billing failure with retry count
```

#### RequestSanitizationService

```
✓ Input validation failures
✓ UUID format validation failures
✓ Suspicious input detection
```

### Log Aggregation

Logs are collected and aggregated for:

- **Development**: Console output via Logback
- **Production**: Centralized log management (ELK, Datadog, CloudWatch, etc.)

**Key queries for production:**

```
# All errors in past hour
ERROR AND timestamp > now-1h

# Payment failures
payment.processing AND level=ERROR

# Webhook retries exceeding 3 attempts
webhook AND retryCount >= 3

# Transactions by status
transaction.status:DECLINED

# Slow API responses
http.response.time > 5000
```

## Distributed Tracing

### Trace Context

Each request gets a unique `traceId` for correlation across services:

```
Request: POST /v1/orders/{orderId}/payments
  │
  ├─ traceId: 550e8400-e29b-41d4-a716-446655440000
  │
  ├─ HTTP Request Handler
  │   │
  │   └─ PaymentOrchestratorService.createPayment()
  │       │
  │       ├─ IdempotencyService.checkIdempotency()
  │       │
  │       ├─ PaymentRepository.save()
  │       │   │
  │       │   └─ Database Query
  │       │
  │       └─ AuthorizeNetGateway.process()
  │           │
  │           └─ Gateway API Call
  │
  └─ Response
```

### Trace Headers

Standard headers for trace correlation:

```
X-Trace-ID: 550e8400-e29b-41d4-a716-446655440000
X-Span-ID: 660e8400-e29b-41d4-a716-446655440111
X-Parent-SPAN-ID: 770e8400-e29b-41d4-a716-446655440222
```

### Span Examples

**Span: HTTP Request**

```
Operation: POST /v1/orders/{orderId}/payments
Duration: 245ms
Tags:
  - http.method: POST
  - http.url: /v1/orders/550e8400-e29b-41d4-a716-446655440000/payments
  - http.status_code: 201
  - http.client_ip: 192.168.1.1
```

**Span: Payment Processing**

```
Operation: PaymentOrchestratorService.createPayment
Duration: 200ms
Tags:
  - orderId: 550e8400-e29b-41d4-a716-446655440000
  - paymentId: 660e8400-e29b-41d4-a716-446655440111
  - gateway: AUTHORIZE_NET
  - status: INITIATED
```

**Span: Gateway Call**

```
Operation: AuthorizeNetGateway.processPurchase
Duration: 150ms
Tags:
  - gateway: AUTHORIZE_NET
  - operation: processPurchase
  - amount: 99.99
  - currency: USD
  - authCode: A123BC
  - status: SETTLED
```

**Span: Database Query**

```
Operation: PaymentRepository.save
Duration: 50ms
Tags:
  - db.type: postgresql
  - db.operation: INSERT
  - db.table: payments
  - rows_affected: 1
```

### Instrumentation Options

For production deployments:

**Option 1: Spring Cloud Sleuth + Zipkin**

```xml
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-sleuth</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-sleuth-zipkin</artifactId>
</dependency>
```

**Option 2: OpenTelemetry**

```xml
<dependency>
  <groupId>io.opentelemetry.instrumentation</groupId>
  <artifactId>opentelemetry-instrumentation-spring-boot-starter</artifactId>
</dependency>
```

**Option 3: Commercial APM (Datadog, New Relic, Dynatrace)**

- Deploy APM agent
- Configure environment variables
- Automatic instrumentation

## Health Checks

### Liveness Probe

```bash
GET /actuator/health/liveness

Response:
{
  "status": "UP",
  "components": {
    "applicationHealth": {"status": "UP"}
  }
}
```

**Indicates**: Application is running

### Readiness Probe

```bash
GET /actuator/health/readiness

Response:
{
  "status": "UP",
  "components": {
    "db": {"status": "UP", "details": {"ping": "success"}},
    "diskSpace": {"status": "UP"},
    "livenessState": {"status": "UP"},
    "readinessState": {"status": "UP"}
  }
}
```

**Indicates**: Application is ready to accept requests

### Startup Probe (Kubernetes)

```bash
GET /actuator/health/startup

Response:
{
  "status": "UP"
}
```

**Indicates**: Startup checks passed

## Monitoring Dashboard

### Key Metrics to Display

**Real-time Status:**

- Application uptime
- Active request count
- Database connection pool status
- JVM memory usage

**Payment Metrics (Last Hour):**

- Orders created (count)
- Payments processed (count)
- Transactions by status (breakdown)
- Payment success rate (%)
- Avg payment processing time (ms)

**Webhook Metrics (Last Hour):**

- Webhooks received (count)
- Webhook processing success rate (%)
- Failed webhooks (count)
- Avg webhook processing time (ms)

**Subscription Metrics:**

- Active subscriptions (count)
- Failed billing cycles (count)
- Subscription creation rate (per day)
- Retry rate (%)

**Error Metrics:**

- Error rate (% of requests)
- Gateway errors (count)
- Database errors (count)
- Authentication failures (count)

**Performance Metrics:**

- p50 response time
- p95 response time
- p99 response time
- Requests per second

## Security Considerations

### Sensitive Data in Logs

**NEVER log:**

- Credit card numbers (masked even if tokens)
- Authentication tokens
- API keys or secrets
- Customer SSNs or IDs

**Always sanitize:**

- Gateway responses (mask auth codes to first 3 chars)
- Customer email (log domain only, e.g., user@\*\*\*\*.com)
- Phone numbers (log last 4 digits only)

### Log Access Control

- Restrict log access to authorized personnel
- Encrypt logs in transit and at rest
- Audit log access
- Retain logs per compliance requirements (typically 7 years for payment data)

## Performance Optimization Tips

### Query Optimization

```sql
-- Enable slow query logging
log_min_duration_statement = 1000  -- 1 second

-- Check query execution plans
EXPLAIN ANALYZE SELECT ...;

-- Index monitoring
SELECT * FROM pg_stat_user_indexes;
```

### Application Tuning

```yaml
# Thread pool settings
spring.task.execution.pool.core-size: 5
spring.task.execution.pool.max-size: 10

# Database connection pool
spring.datasource.hikari.maximum-pool-size: 20
spring.datasource.hikari.minimum-idle: 5

# Logging (reduce overhead)
logging.level.com.paymentgateway: INFO
logging.level.org.springframework: WARN
logging.level.org.hibernate: WARN
```

### Monitoring High-Load Scenarios

- Monitor queue depth (webhook processing backlog)
- Track connection pool utilization
- Monitor garbage collection frequency and duration
- Track p95/p99 response times
- Monitor database lock contention

---

**Last Updated**: January 2026

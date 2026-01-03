# Payment Orchestration Service

A single-tenant payment orchestration service that integrates with Authorize.Net, providing a stable REST API with explicit state management, idempotency guarantees, and webhook processing.

## Features

- **Direct Authorize.Net Integration**: Uses official Authorize.Net Java SDK
- **RESTful API**: Declarative REST APIs for orders, payments, and transactions
- **Explicit State Machine**: Forward-only state transitions with validation
- **Webhook Processing**: Authoritative webhook handling with signature verification
- **Idempotency**: All write operations are idempotent
- **Thread Safety**: Thread-safe operations with locking mechanisms
- **PostgreSQL Database**: JPA-based persistence with Flyway migrations
- **Docker Support**: Docker Compose setup for easy local development
- **API Documentation**: Swagger UI for interactive API exploration

## Architecture

```
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
```

## Payment Flows

### Purchase (Auth + Capture in one step)
1. Create Order
2. Create Payment Intent
3. Commit Purchase Transaction

### Authorize → Capture (2-step)
1. Create Order
2. Create Payment Intent
3. Authorize Transaction
4. Capture Authorized Transaction

## API Endpoints

### Orders
- `POST /v1/orders` - Create order
- `GET /v1/orders/{orderId}` - Get order details

### Payments
- `POST /v1/orders/{orderId}/payments` - Create payment intent

### Transactions
- `POST /v1/payments/{paymentId}/transactions/purchase` - Process purchase (auth + capture)
- `POST /v1/payments/{paymentId}/transactions/authorize` - Authorize only
- `POST /v1/payments/{paymentId}/transactions/{transactionId}/capture` - Capture authorized payment
- `GET /v1/payments/{paymentId}/transactions` - Get all transactions for a payment

### Webhooks
- `POST /v1/webhooks/authorize-net` - Receive Authorize.Net webhooks

## Configuration

Configure Authorize.Net credentials in `application.yml`:

```yaml
authorize:
  net:
    api-login-id: ${AUTHORIZE_NET_API_LOGIN_ID}
    transaction-key: ${AUTHORIZE_NET_TRANSACTION_KEY}
    environment: ${AUTHORIZE_NET_ENVIRONMENT:SANDBOX}
    webhook-signature-key: ${AUTHORIZE_NET_WEBHOOK_SIGNATURE_KEY}
```

## Idempotency

All POST endpoints support idempotency via the `Idempotency-Key` header:

```
Idempotency-Key: <uuid>
```

Same key + endpoint + body hash → same response

## State Machine

### Payment States
- `INITIATED` → `AUTHORIZED` → `CAPTURED`
- `INITIATED` → `FAILED`
- `AUTHORIZED` → `CANCELLED`

### Transaction States
- `REQUESTED` → `AUTHORIZED` / `SUCCESS` / `FAILED`
- `AUTHORIZED` → `SUCCESS` (via capture)
- `SUCCESS` → `SETTLED` (via webhook)

## Webhook Processing

Webhooks are:
- **Authoritative**: Can advance state even if API calls failed
- **Idempotent**: Deduplicated by `gateway_event_id`
- **Verified**: HMAC SHA-256 signature verification
- **Async**: Processed asynchronously to avoid blocking

## Building and Running

### Option 1: Docker Compose (Recommended)

The easiest way to run the application:

```bash
docker-compose up -d
```

This starts PostgreSQL and the application. See [DOCKER_SETUP.md](DOCKER_SETUP.md) for details.

Access:
- Application: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health Check: http://localhost:8080/actuator/health

### Option 2: Local Development

#### Prerequisites
- Java 17+
- Maven 3.6+
- PostgreSQL 15+ (or use Docker for database only)

#### Setup Database
1. Create database:
```sql
CREATE DATABASE payment_gateway;
```

2. Update `application.yml` with your database credentials

#### Build
```bash
mvn clean package
```

#### Run
```bash
mvn spring-boot:run
```

Or:
```bash
java -jar target/payment-orchestration-1.0.0.jar
```

### Database Migrations
Flyway automatically runs migrations on startup. The initial schema is created from `src/main/resources/db/migration/V1__create_initial_schema.sql`.

## API Documentation

Swagger UI is available at:
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI JSON**: http://localhost:8080/v3/api-docs

The API documentation includes:
- All endpoints with request/response schemas
- Example requests
- Error responses
- Authentication details (when configured)

## Testing

The service is designed to be testable with mocks allowed in tests only. The gateway adapter can be mocked for unit and integration tests.
paymentMethodToken: 1234567890ABCDEF1111AAAA2222BBBB3333CCCC4444DDDD5555EEEE6666FFFF7777888899990000

## Database Schema

The system uses PostgreSQL with the following schema:
- **orders**: Business intent representation
- **payments**: Payment lifecycle management
- **payment_transactions**: Immutable transaction ledger (append-only)
- **webhooks**: Webhook event storage with JSONB payloads
- **payment_attempts**: Payment attempt tracking

Key features:
- Immutable transaction ledger
- One order → one payment → one transaction model
- Webhook deduplication by gateway_event_id
- Complete audit trail
- All required indexes for performance

Database migrations are managed by Flyway and run automatically on startup.

## License

This is a private project for payment gateway integration.


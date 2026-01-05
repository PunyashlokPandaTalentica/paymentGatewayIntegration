# Payment Orchestration Service

## Overview

A production-ready **Single-Tenant Payment Orchestration Service** built with Spring Boot 3.2, PostgreSQL, and Authorize.Net integration. This service manages the complete payment lifecycle including order creation, payment processing, transaction management, subscription billing, and webhook handling.

The system implements sophisticated patterns including:

- **Idempotency** to prevent duplicate payments
- **State machines** for reliable payment state tracking
- **Immutable transaction ledger** for audit trail
- **Webhook processing** with asynchronous handling
- **Retry strategies** with exponential backoff
- **OAuth2 JWT authentication** via Auth0

## Quick Start

### Option 1: Docker Compose (Recommended)

The fastest way to get everything running:

```bash
# Clone/navigate to the repository
cd paymentGatewayIntegration

# Create environment file
cp .env.example .env

# Edit .env with your credentials
# Required: AUTHORIZE_NET_API_LOGIN_ID, AUTHORIZE_NET_TRANSACTION_KEY, etc.

# Start all services (PostgreSQL + Application)
docker-compose up -d

# Check status
docker-compose ps

# View logs
docker-compose logs -f app
```

**Access Points:**

- Application: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- API Docs: `http://localhost:8080/v3/api-docs`
- Health Check: `http://localhost:8080/actuator/health`

### Option 2: Local Development

#### Prerequisites

- Java 17+
- Maven 3.6+
- PostgreSQL 15+ running locally
- Auth0 credentials (optional for development)

#### Setup Steps

1. **Create Database**

   ```bash
   # PostgreSQL
   CREATE DATABASE payment_gateway;
   ```

2. **Configure Application**

   ```bash
   # Copy example environment file
   cp .env.example .env

   # Edit .env with your credentials
   ```

3. **Build Project**

   ```bash
   mvn clean package
   ```

4. **Run Application**

   ```bash
   # Using Maven
   mvn spring-boot:run

   # Or using JAR
   java -jar target/payment-orchestration-1.0.0.jar
   ```

5. **Verify Setup**
   ```bash
   curl http://localhost:8080/actuator/health
   # Expected: {"status":"UP"}
   ```

## Database Setup

### Automatic Migration

Flyway automatically runs all migrations on startup. Schema is created from files in `src/main/resources/db/migration/`.

### Manual Database Access

```bash
# Connect to PostgreSQL (Docker)
docker-compose exec postgres psql -U postgres -d payment_gateway

# View tables
\dt

# Check migrations
SELECT * FROM flyway_schema_history;
```

### Database Schema Overview

```
orders (id, merchant_order_id, amount_cents, currency, status, customer_id, created_at)
payments (id, order_id, status, payment_type, gateway, idempotency_key, created_at)
payment_transactions (id, payment_id, transaction_type, state, gateway_response, created_at)
webhooks (id, gateway, event_type, gateway_event_id, payload, processed, created_at)
payment_attempts (id, payment_id, attempt_number, status, error_message, created_at)
subscriptions (id, customer_id, amount_cents, currency, interval, status, next_billing_date)
subscription_payments (id, subscription_id, scheduled_at, processed_at, status, transaction_id)
```

**Key Features:**

- Immutable transaction ledger (append-only)
- Unique indexes on idempotency keys and gateway event IDs
- JSONB support for webhook payloads
- Pessimistic locking for payment updates
- Complete audit trail with timestamps

## Running Background Workers

### Webhook Processing

Webhooks are processed asynchronously:

```bash
# Enable async processing
spring.task.execution.pool.core-size: 5
spring.task.execution.pool.max-size: 10
spring.task.execution.pool.queue-capacity: 100
```

Webhook service:

- Validates signatures
- Detects duplicates (by gateway_event_id)
- Processes asynchronously
- Retries failed events
- Stores in dead letter queue

### Subscription Billing

Subscription-based recurring payments are processed:

```bash
# Daily at 2 AM UTC (configurable)
# Triggers billing for active subscriptions with due next_billing_date
```

Subscription service:

- Automatically creates billing cycles
- Processes recurring charges
- Updates next billing date
- Handles failures with retry logic

## API Endpoints

All endpoints are documented in Swagger UI at `http://localhost:8080/swagger-ui.html`

### Orders

- `POST /v1/orders` - Create order
- `GET /v1/orders/{orderId}` - Get order details

### Payments

- `POST /v1/orders/{orderId}/payments` - Create payment (with idempotency)
- `GET /v1/payments/{paymentId}` - Get payment details

### Transactions

- `POST /v1/payments/{paymentId}/transactions/purchase` - Process purchase (auth + capture)
- `POST /v1/payments/{paymentId}/transactions/authorize` - Authorize payment
- `POST /v1/payments/{paymentId}/transactions/{transactionId}/capture` - Capture authorized payment
- `GET /v1/payments/{paymentId}/transactions` - List transactions

### Subscriptions

- `POST /v1/subscriptions` - Create subscription
- `GET /v1/subscriptions/{subscriptionId}` - Get subscription
- `POST /v1/subscriptions/{subscriptionId}/cancel` - Cancel subscription
- `POST /v1/subscriptions/{subscriptionId}/billing/trigger` - Manually trigger billing

### Webhooks

- `POST /v1/webhooks/authorize-net` - Receive Authorize.Net webhooks (signature validated)

## Configuration

### Environment Variables

```bash
# Database
DATABASE_URL=jdbc:postgresql://localhost:5432/payment_gateway
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=your_secure_password

# Authorize.Net
AUTHORIZE_NET_API_LOGIN_ID=your_api_login_id
AUTHORIZE_NET_TRANSACTION_KEY=your_transaction_key
AUTHORIZE_NET_WEBHOOK_SIGNATURE_KEY=your_signature_key
AUTHORIZE_NET_ENVIRONMENT=SANDBOX  # or PRODUCTION

# Auth0 (Optional)
OAUTH2_ISSUER_URI=https://your_tenant.us.auth0.com/
OAUTH2_AUDIENCE=https://api.paymentgateway.com
SECURITY_ENABLED=true

# Application
SERVER_PORT=8080
APP_REPOSITORY_TYPE=jpa
```

### Key Configuration Files

- `src/main/resources/application.yml` - Main configuration
- `src/main/resources/application-docker.yml` - Docker-specific config
- `docker-compose.yml` - Docker Compose configuration
- `.env.example` - Environment variable template

## Architecture Highlights

### Payment State Machine

Payments transition through states based on transactions:

```
INITIATED → AUTHORIZED → CAPTURED → SETTLED
         → DECLINED
         → FAILED
```

### Idempotency

All payment operations support idempotency via `Idempotency-Key` header. Duplicate requests with the same key return the same response.

### Webhook Processing

- Validates Authorize.Net signatures
- Detects duplicates by `gateway_event_id`
- Processes asynchronously
- Retries on failure
- Stores in dead letter queue for manual intervention

### Subscription Billing

- Automatic scheduling
- Recurring charge processing
- Failed payment retry logic
- Configurable intervals (daily, weekly, monthly, yearly)

## Testing

### Run All Tests

```bash
mvn test
```

### Run Specific Test Class

```bash
mvn test -Dtest=OrderIntegrationTest
```

### Generate Coverage Report

```bash
mvn jacoco:report
# Report available at: target/site/jacoco/index.html
```

### Test Categories

- **Unit Tests**: Service logic, state machines, validation
- **Integration Tests**: Database, JPA repository operations
- **E2E Tests**: API endpoints, complete workflows
- **Performance Tests**: Concurrent operations, load testing

See [TESTING_STRATEGY.md](TESTING_STRATEGY.md) for detailed testing plan.

## Monitoring & Observability

### Health Checks

```bash
curl http://localhost:8080/actuator/health
```

### Logging

Application uses SLF4J with Logback:

- **INFO**: Normal operations
- **WARN**: Unusual but handled conditions
- **ERROR**: Failure scenarios
- **DEBUG**: Detailed flow (development only)

Log files are output to console in Docker and to `logs/` directory locally.

### Metrics

Available via Actuator:

- `http.server.requests` - HTTP request metrics
- `jpa.session.open_transactions` - JPA transaction count
- `process.cpu.usage` - CPU usage
- `jvm.memory.usage` - Memory metrics
- `db.postgresql.connections` - Database connections

See [OBSERVABILITY.md](OBSERVABILITY.md) for detailed metrics and tracing strategy.

## Security

### Authentication

- OAuth2 with JWT tokens
- Auth0 integration
- Scope-based authorization

### API Security

- Request validation
- Input sanitization
- UUID format validation
- Idempotency checks
- Rate limiting (via infrastructure)

### Secrets Management

- Never commit `.env` files
- Use strong passwords
- Rotate credentials regularly
- Use `SECRETS.md` for detailed guidelines

## Troubleshooting

### Application Won't Start

```bash
# Check logs
docker-compose logs app

# Verify database is ready
docker-compose ps

# Check database connection
docker-compose logs postgres
```

### Port Already in Use

```bash
# Change port in docker-compose.yml or:
lsof -i :8080  # Find process using port
kill -9 <PID>  # Kill process
```

### Database Connection Issues

```bash
# Connect directly to test connection
docker-compose exec postgres psql -U postgres -d payment_gateway

# Check Flyway migrations
SELECT * FROM flyway_schema_history;
```

### Webhook Signature Validation Failures

- Verify webhook signature key matches your Authorize.Net account
- Check timestamp freshness (within 5 minutes)
- Verify payload hasn't been modified

## Building & Deploying

### Build Docker Image

```bash
docker build -t payment-orchestration:latest .
```

### Docker Compose Operations

```bash
# Start services
docker-compose up -d

# Stop services
docker-compose down

# Stop and remove data
docker-compose down -v

# Rebuild images
docker-compose up -d --build

# View logs
docker-compose logs -f app

# Execute command in container
docker-compose exec app sh
```

## Development

### Project Structure

See [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) for detailed folder organization and module descriptions.

### Architecture

See [Architecture.md](Architecture.md) for system design, entity relationships, and design trade-offs.

### Testing Strategy

See [TESTING_STRATEGY.md](TESTING_STRATEGY.md) for comprehensive testing plan.

## Dependencies

Key libraries:

- Spring Boot 3.2.0
- Spring Data JPA
- Hibernate ORM
- PostgreSQL JDBC Driver
- Authorize.Net SDK
- SpringDoc OpenAPI (Swagger)
- Lombok
- JUnit 5, Mockito (testing)
- Flyway (database migrations)
- Auth0 Spring Security

Full list: See [pom.xml](pom.xml)

## License

This is a private project for payment gateway integration.

## Support & Contact

For issues or questions:

1. Check [TROUBLESHOOTING](#troubleshooting) section
2. Review error logs
3. Consult [Architecture.md](Architecture.md) for design details
4. See [DOCKER_SETUP.md](DOCKER_SETUP.md) for Docker-specific help

---

**Last Updated**: January 2026  
**Project Status**: Production-Ready with OAuth2 Auth0 Integration

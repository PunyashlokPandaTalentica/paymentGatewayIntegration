# Project Structure

## Directory Organization

```
paymentGatewayIntegration/
├── src/
│   ├── main/
│   │   ├── java/com/paymentgateway/
│   │   │   ├── PaymentOrchestrationApplication.java    # Application entry point
│   │   │   ├── api/
│   │   │   │   ├── controller/                         # REST API Controllers
│   │   │   │   │   ├── OrderController.java            # Order endpoints
│   │   │   │   │   ├── PaymentController.java          # Payment endpoints
│   │   │   │   │   ├── TransactionController.java      # Transaction endpoints
│   │   │   │   │   ├── SubscriptionController.java     # Subscription endpoints
│   │   │   │   │   └── WebhookController.java          # Webhook endpoints
│   │   │   │   ├── dto/                                # Request/Response DTOs
│   │   │   │   │   ├── CreateOrderRequest.java
│   │   │   │   │   ├── CreatePaymentRequest.java
│   │   │   │   │   ├── OrderResponse.java
│   │   │   │   │   ├── PaymentResponse.java
│   │   │   │   │   ├── TransactionResponse.java
│   │   │   │   │   ├── WebhookRequest.java
│   │   │   │   │   ├── ErrorResponse.java
│   │   │   │   │   └── SubscriptionResponse.java
│   │   │   │   ├── exception/                          # Global exception handling
│   │   │   │   │   └── GlobalExceptionHandler.java     # Centralized error handling
│   │   │   │   └── service/                            # API-layer services
│   │   │   │       └── RequestSanitizationService.java # Input validation & sanitization
│   │   │   │
│   │   │   ├── config/                                 # Spring configuration
│   │   │   │   ├── OpenApiConfig.java                  # Swagger/OpenAPI setup
│   │   │   │   ├── SecurityConfig.java                 # OAuth2 JWT security
│   │   │   │   └── AsyncConfig.java                    # Async processing config
│   │   │   │
│   │   │   ├── domain/                                 # Domain models & business logic
│   │   │   │   ├── model/                              # Core domain models
│   │   │   │   │   ├── Order.java                      # Order aggregate root
│   │   │   │   │   ├── Payment.java                    # Payment aggregate root
│   │   │   │   │   ├── PaymentTransaction.java         # Immutable transaction record
│   │   │   │   │   ├── Customer.java                   # Customer value object
│   │   │   │   │   ├── WebhookEvent.java               # Webhook event entity
│   │   │   │   │   ├── Subscription.java               # Subscription aggregate
│   │   │   │   │   ├── SubscriptionPayment.java        # Recurring payment record
│   │   │   │   │   └── PaymentAttempt.java             # Payment attempt tracking
│   │   │   │   │
│   │   │   │   ├── enums/                              # Domain enumerations
│   │   │   │   │   ├── OrderStatus.java                # CREATED, PAYMENT_PENDING, PAID, FAILED, CANCELLED
│   │   │   │   │   ├── PaymentStatus.java              # INITIATED, AUTHORIZED, CAPTURED, SETTLED, DECLINED
│   │   │   │   │   ├── PaymentType.java                # PURCHASE, AUTHORIZE
│   │   │   │   │   ├── TransactionState.java           # SUBMITTED, AUTHORIZED, SETTLED, DECLINED, VOIDED
│   │   │   │   │   ├── TransactionType.java            # PURCHASE, AUTHORIZATION, CAPTURE
│   │   │   │   │   ├── Gateway.java                    # AUTHORIZE_NET
│   │   │   │   │   ├── RecurrenceInterval.java         # DAILY, WEEKLY, MONTHLY, YEARLY
│   │   │   │   │   └── SubscriptionStatus.java         # ACTIVE, PAUSED, CANCELLED, EXPIRED
│   │   │   │   │
│   │   │   │   └── valueobject/                        # Value objects
│   │   │   │       └── Money.java                      # Amount + Currency
│   │   │   │
│   │   │   ├── gateway/                                # Payment gateway integration
│   │   │   │   ├── RetryableGatewayService.java        # Abstract gateway with retry logic
│   │   │   │   ├── AuthorizeNetGateway.java            # Authorize.Net implementation
│   │   │   │   ├── dto/                                # Gateway response DTOs
│   │   │   │   │   ├── PurchaseResponse.java
│   │   │   │   │   ├── AuthResponse.java
│   │   │   │   │   └── CaptureResponse.java
│   │   │   │   └── exception/                          # Gateway-specific exceptions
│   │   │   │       └── GatewayException.java
│   │   │   │
│   │   │   ├── repository/                             # Data persistence layer
│   │   │   │   ├── jpa/                                # JPA repositories (Spring Data)
│   │   │   │   │   ├── JpaOrderRepository.java
│   │   │   │   │   ├── JpaPaymentRepository.java
│   │   │   │   │   ├── JpaPaymentTransactionRepository.java
│   │   │   │   │   ├── JpaWebhookRepository.java
│   │   │   │   │   └── JpaSubscriptionRepository.java
│   │   │   │   ├── entity/                             # JPA entity classes
│   │   │   │   │   ├── OrderEntity.java
│   │   │   │   │   ├── PaymentEntity.java
│   │   │   │   │   ├── PaymentTransactionEntity.java
│   │   │   │   │   ├── WebhookEventEntity.java
│   │   │   │   │   ├── SubscriptionEntity.java
│   │   │   │   │   └── SubscriptionPaymentEntity.java
│   │   │   │   ├── mapper/                             # Entity ↔ Domain model mappers
│   │   │   │   │   ├── OrderMapper.java
│   │   │   │   │   ├── PaymentMapper.java
│   │   │   │   │   ├── PaymentTransactionMapper.java
│   │   │   │   │   ├── WebhookMapper.java
│   │   │   │   │   └── SubscriptionMapper.java
│   │   │   │   └── interfaces/                         # Repository interfaces
│   │   │   │       ├── OrderRepository.java
│   │   │   │       ├── PaymentRepository.java
│   │   │   │       ├── PaymentTransactionRepository.java
│   │   │   │       ├── WebhookRepository.java
│   │   │   │       └── SubscriptionRepository.java
│   │   │   │
│   │   │   └── service/                                # Business logic layer
│   │   │       ├── PaymentOrchestratorService.java     # Main orchestration service
│   │   │       ├── PaymentStateMachine.java            # Payment state transitions
│   │   │       ├── WebhookProcessorService.java        # Webhook event processing
│   │   │       ├── WebhookSignatureService.java        # Signature validation
│   │   │       ├── SubscriptionService.java            # Subscription management
│   │   │       ├── RecurringPaymentService.java        # Recurring payment processing
│   │   │       ├── DeadLetterQueueService.java         # Failed webhook handling
│   │   │       ├── IdempotencyService.java             # Idempotency key management
│   │   │       └── exception/                          # Service-specific exceptions
│   │   │           └── PaymentProcessingException.java
│   │   │
│   │   └── resources/
│   │       ├── application.yml                         # Main configuration
│   │       ├── application-docker.yml                  # Docker-specific config
│   │       └── db/migration/                           # Flyway database migrations
│   │           ├── V1__create_initial_schema.sql       # Initial schema
│   │           ├── V2__alter_currency_columns_to_varchar.sql
│   │           ├── V3__create_failed_webhooks_table.sql
│   │           ├── V4__create_subscriptions_tables.sql
│   │           ├── V5__replace_payment_method_token_with_customer_profile_id.sql
│   │           ├── V6__updateCustomerId.sql
│   │           └── V7__add_subscription_payment_profile_id.sql
│   │
│   └── test/
│       ├── java/com/paymentgateway/
│       │   ├── integration/                            # Integration tests
│       │   │   ├── OrderIntegrationTest.java           # Order API tests
│       │   │   ├── PaymentIntegrationTest.java         # Payment API tests
│       │   │   ├── TransactionIntegrationTest.java     # Transaction API tests
│       │   │   ├── WebhookIntegrationTest.java         # Webhook processing tests
│       │   │   ├── SubscriptionIntegrationTest.java    # Subscription tests
│       │   │   └── RecurringPaymentIntegrationTest.java
│       │   └── unit/                                   # Unit tests
│       │       ├── PaymentStateMachineTest.java
│       │       ├── PaymentOrchestratorServiceTest.java
│       │       └── IdempotencyServiceTest.java
│       └── resources/
│           └── application-test.yml                    # Test configuration (H2)
│
├── frontend/                                           # Frontend (optional)
│   ├── index.html                                      # Simple HTML interface
│   └── https_server.py                                 # Development HTTPS server
│
├── scripts/                                            # Utility scripts
│   ├── get-auth0-token.sh                              # Auth0 token retrieval
│   └── auth0-quick-reference.md                        # Auth0 setup guide
│
├── docker-compose.yml                                  # Docker Compose configuration
├── Dockerfile                                          # Docker build file
├── pom.xml                                             # Maven dependencies
├── .env.example                                        # Environment template
├── .gitignore                                          # Git ignore rules
│
├── README.md                                           # Main documentation
├── PROJECT_STRUCTURE.md                                # This file
├── Architecture.md                                     # Architecture & design
├── OBSERVABILITY.md                                    # Metrics & logging
├── API-SPECIFICATION.yml                               # OpenAPI spec
├── TESTING_STRATEGY.md                                 # Testing plan
├── TEST_REPORT.md                                      # Test coverage
├── DOCKER_SETUP.md                                     # Docker guide
├── SECRETS.md                                          # Secrets management
├── AUTH0_SETUP.md                                      # Auth0 integration
│
└── TODO/
    ├── TODO.md                                         # Work in progress
    ├── COMPLETED.md                                    # Completed items
    └── PENDING.md                                      # Pending tasks
```

## Layer Architecture

### Presentation Layer (API)

- **Controllers**: Handle HTTP requests/responses
- **DTOs**: Data transfer objects for API contracts
- **Exception Handling**: Centralized error handling with GlobalExceptionHandler
- **Validation**: Input validation via annotations and RequestSanitizationService

### Domain Layer (Business Logic)

- **Models**: Core domain entities (Order, Payment, PaymentTransaction, etc.)
- **Value Objects**: Money (amount + currency), Customer info
- **Enumerations**: Status, state, and type enumerations
- **Services**: PaymentOrchestratorService, WebhookProcessorService, SubscriptionService
- **State Machine**: PaymentStateMachine for reliable state transitions

### Gateway Layer (Payment Processing)

- **RetryableGatewayService**: Abstract base with retry logic
- **AuthorizeNetGateway**: Authorize.Net implementation
- **Response DTOs**: Structured gateway responses
- **Exception Handling**: Gateway-specific exceptions

### Persistence Layer (Data Access)

- **JPA Repositories**: Spring Data JPA for database access
- **Entities**: JPA entity classes mapped to database tables
- **Mappers**: Convert between entities and domain models
- **Repository Interfaces**: Abstraction for data access

## Key Components

### Order

- **Responsibility**: Represent business intent for a payment
- **Aggregate Root**: Contains customer, amount, description
- **State**: CREATED → PAYMENT_PENDING → PAID / FAILED / CANCELLED
- **Key Attributes**: id, merchantOrderId (unique), amount, customer

### Payment

- **Responsibility**: Manage payment lifecycle
- **Aggregate Root**: Linked to order, supports one payment per order
- **State**: INITIATED → AUTHORIZED → CAPTURED → SETTLED
- **Idempotency**: Idempotency-Key header prevents duplicate payments
- **Key Attributes**: id, orderId, status, paymentType, gateway

### PaymentTransaction

- **Responsibility**: Immutable record of gateway interactions
- **Immutable**: Append-only, never updated
- **Types**: PURCHASE (auth+capture), AUTHORIZATION, CAPTURE
- **State**: SUBMITTED → AUTHORIZED → SETTLED / DECLINED
- **Audit Trail**: Complete history of all transactions

### WebhookEvent

- **Responsibility**: Store and process webhook events from Authorize.Net
- **Signature Validation**: HMAC-SHA512 signature verification
- **Deduplication**: By gateway_event_id to prevent duplicate processing
- **Async Processing**: Processed asynchronously with retry logic

### Subscription

- **Responsibility**: Manage recurring/subscription-based payments
- **Billing Cycles**: Automatic scheduling at next_billing_date
- **Intervals**: Daily, weekly, monthly, yearly
- **Failure Handling**: Automatic retry with configurable limits

## Database Schema Highlights

### Immutability

- `payment_transactions` is append-only (no updates)
- Ensures complete audit trail

### Uniqueness Constraints

- `orders.merchant_order_id` - Merchant's unique order identifier
- `payments.idempotency_key` - Prevents duplicate payments
- `webhooks.gateway_event_id` - Prevents duplicate webhook processing

### Indexes for Performance

- `idx_orders_merchant_id` - Fast merchant order lookup
- `idx_payments_order_id` - Fast payment by order lookup
- `idx_transactions_payment_id` - Fast transaction list lookup
- `idx_webhooks_gateway_event_id` - Fast webhook deduplication

### Foreign Keys

- `payments.order_id` → `orders.id`
- `payment_transactions.payment_id` → `payments.id`
- `subscription_payments.subscription_id` → `subscriptions.id`

## Configuration Files

### application.yml (Main)

- Database connection (PostgreSQL)
- JPA/Hibernate configuration
- Flyway database migration settings
- Authorize.Net credentials
- Auth0 OAuth2 configuration
- Server port and error handling

### application-docker.yml

- Docker-specific overrides
- Environment variable injection
- PostgreSQL connection pooling
- Health check configuration

### application-test.yml

- H2 in-memory database for testing
- Flyway disabled (use Hibernate DDL)
- Test-specific credentials
- No Auth0 enforcement in tests

## Dependency Management (pom.xml)

### Spring Boot Starters

- spring-boot-starter-web: REST API support
- spring-boot-starter-data-jpa: Database ORM
- spring-boot-starter-security: OAuth2 support
- spring-boot-starter-validation: Bean validation

### Database

- postgresql: PostgreSQL driver
- flyway-core: Database migrations
- h2 (test): In-memory database for testing

### API Documentation

- springdoc-openapi-starter-webmvc-ui: Swagger UI

### Utilities

- lombok: Boilerplate reduction
- authorize-net-java-sdk: Authorize.Net integration

### Testing

- spring-boot-starter-test: JUnit 5, Mockito, AssertJ
- testcontainers: PostgreSQL test container

## Important Patterns

### Idempotency

Implemented at multiple levels:

1. API level: Idempotency-Key header
2. Service level: Payment repository check
3. Database level: Unique index on idempotency_key

### State Machine

PaymentStateMachine determines payment state based on transactions:

```
No transactions → INITIATED
1+ AUTHORIZATION → AUTHORIZED
1+ CAPTURE → CAPTURED
1+ SETTLED → SETTLED
1+ DECLINED → DECLINED
```

### Async Processing

- Webhook processing: @Async on WebhookProcessorService
- Subscription billing: Scheduled task with @Scheduled

### Error Handling

- GlobalExceptionHandler: Centralized exception handling
- PaymentProcessingException: Domain-specific exceptions
- GatewayException: Gateway integration errors
- DeadLetterQueue: Stores failed webhooks for manual retry

---

**Note**: This structure supports clean architecture principles with clear separation of concerns, testability, and maintainability. Each layer has a specific responsibility and can be tested independently.

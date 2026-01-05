# Testing Strategy

## Overview

This document outlines the comprehensive testing strategy for the Payment Orchestration Service. Testing ensures reliability, correctness, and performance across all layers of the application.

## Testing Pyramid

```
                    ┌─────────────┐
                    │  E2E Tests  │ (10%)
                    │ Slow, Brittle
                    ├─────────────┤
                    │Integration  │ (20%)
                    │Tests        │
                    ├─────────────┤
                    │   Unit      │ (70%)
                    │   Tests     │
                    │Fast, Stable │
                    └─────────────┘
```

## Unit Testing (70% of tests)

### Scope

- Individual methods and classes in isolation
- Business logic validation
- Edge cases and boundary conditions
- Error handling

### Test Coverage Targets

- Service layer: **> 90% coverage**
- Domain models: **> 85% coverage**
- Utilities/Helpers: **> 80% coverage**
- Controllers: **> 70% coverage** (mostly handled by integration tests)

### Key Unit Test Classes

#### PaymentStateMachineTest

```java
@DisplayName("Payment State Machine Tests")
class PaymentStateMachineTest {

  @Test
  void derivesInitiatedStateWhenNoTransactions() {...}

  @Test
  void derivesAuthorizedStateWhenAuthorizationExists() {...}

  @Test
  void derivesCapturedStateWhenCaptureExists() {...}

  @Test
  void derivesSettledStateFromSettledTransaction() {...}

  @Test
  void derivesDeclinedStateFromDeclinedTransaction() {...}

  @Test
  void throwsExceptionOnInvalidStateTransition() {...}
}
```

#### PaymentOrchestratorServiceTest

```java
@DisplayName("Payment Orchestrator Service Tests")
class PaymentOrchestratorServiceTest {

  @Test
  void createsOrderWithValidRequest() {...}

  @Test
  void validatesRequiredOrderFields() {...}

  @Test
  void rejectsOrderWithDuplicateMerchantId() {...}

  @Test
  void createsPaymentWithIdempotencyKey() {...}

  @Test
  void returnsSamePaymentForDuplicateIdempotencyKey() {...}

  @Test
  void processesPurchaseTransaction() {...}

  @Test
  void processesAuthorizationTransaction() {...}

  @Test
  void throwsExceptionOnInvalidGateway() {...}
}
```

#### IdempotencyServiceTest

```java
@DisplayName("Idempotency Service Tests")
class IdempotencyServiceTest {

  @Test
  void generateUniqueIdempotencyKey() {...}

  @Test
  void detectsDuplicateIdempotencyKey() {...}

  @Test
  void returnsExistingPaymentForDuplicateKey() {...}
}
```

#### WebhookSignatureServiceTest

```java
@DisplayName("Webhook Signature Validation Tests")
class WebhookSignatureServiceTest {

  @Test
  void validatesCorrectSignature() {...}

  @Test
  void rejectsInvalidSignature() {...}

  @Test
  void rejectsExpiredWebhook() {...}

  @Test
  void handlesSignatureCaseSensitivity() {...}
}
```

### Unit Test Structure

```java
@DisplayName("Class/Method Description")
class ServiceTest {

  private ServiceUnderTest service;

  @Mock
  private DependencyA dependencyA;

  @Mock
  private DependencyB dependencyB;

  @BeforeEach
  void setUp() {
    // Initialize test fixtures
    MockitoAnnotations.openMocks(this);
    service = new ServiceUnderTest(dependencyA, dependencyB);
  }

  @DisplayName("Should ... when ...")
  @Test
  void testValidScenario() {
    // Given
    String input = "test";

    // When
    Result result = service.doSomething(input);

    // Then
    assertEquals(expected, result);
    verify(dependencyA).method();
  }

  @DisplayName("Should throw exception when ...")
  @Test
  void testErrorScenario() {
    // Given
    String invalid = null;

    // When & Then
    assertThrows(IllegalArgumentException.class,
      () -> service.doSomething(invalid));
  }

  @DisplayName("Should handle edge case ...")
  @Test
  void testEdgeCase() {
    // Test boundary conditions, empty collections, etc.
  }
}
```

## Integration Testing (20% of tests)

### Scope

- Database integration (JPA repositories)
- Service layer with real dependencies
- Complete workflow testing
- Database transaction handling
- Flyway migration validation

### Test Infrastructure

**TestContainers with PostgreSQL:**

```java
@SpringBootTest
@Testcontainers
class IntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
    new PostgreSQLContainer<>("postgres:15");

  // Tests run against real PostgreSQL instance
}
```

**Alternative: H2 In-Memory Database**

```yaml
# application-test.yml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
    properties:
      hibernate.dialect: org.hibernate.dialect.H2Dialect
  flyway:
    enabled: false # Let Hibernate create schema
```

### Key Integration Test Classes

#### OrderIntegrationTest

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderIntegrationTest {

  @Test
  void createsOrderAndPersistsToDatabase() {...}

  @Test
  void retrievesOrderByMerchantId() {...}

  @Test
  void enforcesUniqueMerchantOrderId() {...}

  @Test
  void transactionRollsBackOnConstraintViolation() {...}
}
```

#### PaymentIntegrationTest

```java
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaymentIntegrationTest {

  @Test
  void createsPaymentAndPersistsToDatabase() {...}

  @Test
  void supportsIdempotencyWithDuplicateKey() {...}

  @Test
  void enforcesOnePaymentPerOrder() {...}

  @Test
  void appliesPessimisticLockingOnUpdate() {...}
}
```

#### WebhookIntegrationTest

```java
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WebhookIntegrationTest {

  @Test
  void receivesAndProcessesWebhook() {...}

  @Test
  void detectionsDuplicateWebhookByEventId() {...}

  @Test
  void validatesWebhookSignature() {...}

  @Test
  void processingFailureStoresInDeadLetterQueue() {...}
}
```

#### SubscriptionIntegrationTest

```java
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubscriptionIntegrationTest {

  @Test
  void createsSubscriptionAndPersistsToDatabase() {...}

  @Test
  void calculatesNextBillingDateCorrectly() {...}

  @Test
  void enforcesUniqueMerchantSubscriptionId() {...}

  @Test
  void handlesBillingCycleIncrement() {...}
}
```

## API Testing (E2E - 10% of tests)

### Scope

- Full request/response flow
- HTTP status codes and headers
- API contract validation
- Error responses
- Header validation (Idempotency-Key)

### REST Assured for API Testing

```xml
<dependency>
  <groupId>io.rest-assured</groupId>
  <artifactId>rest-assured</artifactId>
  <scope>test</scope>
</dependency>
```

### Example E2E Test

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerE2ETest {

  @LocalServerPort
  private int port;

  private String baseUrl;

  @BeforeEach
  void setUp() {
    baseUrl = "http://localhost:" + port;
    RestAssured.basePath = "";
  }

  @Test
  void createsOrderViaAPI() {
    CreateOrderRequest request = new CreateOrderRequest(
      "ORD-12345",
      new Money(BigDecimal.valueOf(99.99), Currency.getInstance("USD")),
      "Test order",
      new Customer("test@example.com", "+1234567890")
    );

    given()
      .basePath(baseUrl)
      .contentType("application/json")
      .body(request)
    .when()
      .post("/v1/orders")
    .then()
      .statusCode(201)
      .body("orderId", notNullValue())
      .body("status", equalTo("CREATED"))
      .body("amount.value", equalTo(99.99F));
  }
}
```

## Scenario Testing

### Test Scenarios

#### Scenario 1: Successful Purchase Flow

```
1. Create Order
   - Verify: OrderStatus = CREATED
2. Create Payment (PURCHASE flow)
   - Verify: PaymentStatus = INITIATED
3. POST /transactions/purchase
   - Mock: AuthorizeNet returns SUCCESS
   - Verify: PaymentStatus = CAPTURED → SETTLED
4. Receive Settlement Webhook
   - Verify: WebhookEvent processed, Payment updated
```

#### Scenario 2: Authorization + Capture Flow

```
1. Create Order
2. Create Payment (AUTHORIZE flow)
3. POST /transactions/authorize
   - Mock: AuthorizeNet returns AUTHORIZED
   - Verify: PaymentStatus = AUTHORIZED
4. POST /transactions/{id}/capture
   - Mock: AuthorizeNet returns CAPTURED
   - Verify: PaymentStatus = CAPTURED
5. Receive Settlement Webhook
   - Verify: PaymentStatus = SETTLED
```

#### Scenario 3: Idempotency Check

```
1. Create Order
2. POST /orders/{orderId}/payments with Idempotency-Key: "KEY-123"
   - Response: PaymentStatus = INITIATED
3. POST /orders/{orderId}/payments with same Idempotency-Key: "KEY-123"
   - Response: Same PaymentID, same response
   - Database: Only one payment record
```

#### Scenario 4: Webhook Processing

```
1. Create Order, Payment, Transaction
2. Webhook: TransactionSettled event
   - Verify: Signature validation passes
   - Verify: ProcessedInAsync task
   - Verify: PaymentStatus updated
3. Same Webhook (duplicate)
   - Verify: Ignored (gateway_event_id unique constraint)
```

#### Scenario 5: Subscription Billing

```
1. Create Subscription
   - Verify: SubscriptionStatus = ACTIVE
   - Verify: next_billing_date set
2. Billing Job triggered (next_billing_date reached)
   - Verify: Charge processed via gateway
   - Verify: next_billing_date incremented
3. Billing Failure
   - Verify: Retry with exponential backoff
   - Verify: Max retries exceeded → Subscription CANCELLED
```

## Test Data Management

### Fixtures and Builders

```java
class TestDataBuilder {

  public static Order.OrderBuilder orderBuilder() {
    return Order.builder()
      .id(UUID.randomUUID())
      .merchantOrderId("ORD-" + UUID.randomUUID())
      .amount(new Money(BigDecimal.valueOf(99.99), Currency.getInstance("USD")))
      .customer(new Customer("test@example.com", "+1234567890"))
      .status(OrderStatus.CREATED);
  }

  public static Payment.PaymentBuilder paymentBuilder() {
    return Payment.builder()
      .id(UUID.randomUUID())
      .status(PaymentStatus.INITIATED)
      .paymentType(PaymentType.PURCHASE)
      .gateway(Gateway.AUTHORIZE_NET);
  }
}
```

### Clean Database Between Tests

```java
@BeforeEach
void cleanDatabase() {
  // @Transactional rollback after each test
  // OR
  paymentRepository.deleteAll();
  orderRepository.deleteAll();
}
```

## Mocking Strategy

### Mocking Levels

| Component               | Approach     | Why                              |
| ----------------------- | ------------ | -------------------------------- |
| AuthorizeNetGateway     | @MockBean    | Avoid real API calls, fast tests |
| PaymentRepository       | Real (JPA)   | Test persistence                 |
| ExternalAPIs            | @Mock        | No network dependency            |
| WebhookSignatureService | Real or Mock | Validate logic or speed          |

### Example Mocking

```java
@MockBean
private RetryableGatewayService gatewayService;

@BeforeEach
void setupMocks() {
  when(gatewayService.processPurchase(any()))
    .thenReturn(PurchaseResponse.builder()
      .authCode("AUTH123")
      .transactionRefId("REF456")
      .state(TransactionState.SETTLED)
      .build());
}

@Test
void processesPaymentWithMockedGateway() {
  // Test uses mock gateway response
  Payment payment = service.createPayment(...);
  assertEquals(PaymentStatus.CAPTURED, payment.getStatus());

  // Verify mock was called
  verify(gatewayService).processPurchase(any());
}
```

## Test Configuration

### Test Profiles

```yaml
# src/test/resources/application-test.yml

spring:
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
  flyway:
    enabled: false

app:
  repository:
    type: jpa

authorize:
  net:
    api-login-id: test-api-login-id
    transaction-key: test-transaction-key
    environment: SANDBOX
```

### Test Annotations

```java
@SpringBootTest              // Full context
@DataJpaTest                 // Only JPA/database layer
@WebMvcTest                  // Only MVC layer
@ActiveProfiles("test")      // Use test config
@Transactional               // Rollback after test
@DisplayName("...")          // Clear test description
@ParameterizedTest           // Test multiple inputs
@CsvSource(...)              // Test parameters
@Mock / @MockBean            // Mocking
@Captor                      // Capture arguments
```

## Continuous Integration

### Test Execution in CI/CD

```yaml
# GitHub Actions example
test:
  script:
    # Unit tests
    - mvn test -P unit

    # Integration tests
    - mvn test -P integration

    # Coverage report
    - mvn jacoco:report

    # Generate test report
    - mvn surefire-report:report
```

### Coverage Requirements

| Module        | Minimum Coverage | Target |
| ------------- | ---------------- | ------ |
| Services      | 80%              | 90%    |
| Domain Models | 75%              | 85%    |
| Repositories  | 70%              | 80%    |
| Controllers   | 60%              | 70%    |
| Overall       | 75%              | 85%    |

### Coverage Reports

```bash
# Generate coverage report
mvn clean test jacoco:report

# View report
open target/site/jacoco/index.html
```

## Performance Testing

### Load Testing Goals

- Concurrent orders: 100+ /sec
- Payment processing: < 500ms p95
- Webhook processing: < 1000ms p95
- Database connections: < 20 of 30

### JMeter Test Plan

```
Thread Group (100 threads, 10 ramp-up)
├── Create Order
│   └── POST /v1/orders
├── Create Payment
│   └── POST /v1/orders/{orderId}/payments
└── Process Transaction
    └── POST /v1/payments/{paymentId}/transactions/purchase

Assertions:
├── Response Code: 201
├── Response Time < 500ms
└── No errors
```

### Gatling Test Example

```scala
val scn = scenario("Payment Processing Load Test")
  .exec(http("Create Order")
    .post("/v1/orders")
    .body(...))
  .exec(http("Create Payment")
    .post("/v1/orders/${orderId}/payments")
    .body(...))

setUp(scn.inject(
  rampUsers(100) during (60 seconds)
).protocols(httpProtocol))
```

## Security Testing

### Test Cases

- ✅ Invalid JWT token rejected
- ✅ Missing authentication header rejected
- ✅ SQL injection attempts fail
- ✅ XSS attempts sanitized
- ✅ Sensitive data not in logs
- ✅ Webhook signature validation mandatory

### OWASP Testing

- Input validation
- Authentication/authorization
- Session management
- Cross-site scripting (XSS)
- SQL injection
- Sensitive data exposure
- Broken access control

## Running Tests

### Run All Tests

```bash
mvn test
```

### Run Specific Test Class

```bash
mvn test -Dtest=OrderControllerTest
```

### Run Specific Test Method

```bash
mvn test -Dtest=OrderControllerTest#testCreateOrder
```

### Run Integration Tests Only

```bash
mvn test -P integration
```

### Run with Coverage

```bash
mvn clean test jacoco:report
```

### Run Performance Tests

```bash
mvn gatling:test
```

## Test Reporting

### Surefire Report

```bash
mvn surefire-report:report
# Report: target/site/surefire-report.html
```

### JaCoCo Coverage Report

```bash
mvn jacoco:report
# Report: target/site/jacoco/index.html
```

### Aggregated Reports

```bash
mvn site
# Reports: target/site/
```

## Best Practices

### ✅ DO

- Use descriptive test names
- One assertion per test (or related assertions)
- Use @DisplayName for clarity
- Use test fixtures and builders
- Mock external dependencies
- Use @ActiveProfiles("test")
- Test both happy and sad paths
- Use @Transactional for isolation
- Document complex test logic

### ❌ DON'T

- Don't test Spring's internal code
- Don't skip database tests
- Don't mix unit and integration tests
- Don't test implementation details
- Don't use sleep() for synchronization
- Don't share test data between tests
- Don't test multiple scenarios in one test
- Don't hardcode test data

---

**Last Updated**: January 2026

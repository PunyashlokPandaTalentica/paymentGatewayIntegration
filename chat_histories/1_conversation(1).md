***You***: Create Initial Architecture document, requirement document to share with cursor for building the application
***ChatGPT***: Below is a **concise but complete Initial Architecture + Requirements Document** you can directly share with **Cursor** (or paste into a repo as `ARCHITECTURE.md` / `REQUIREMENTS.md`).

This is aligned with your **Payment Processing System** discussions: Spring Boot, Auth.Net SDK, state machine, idempotency, webhooks, in-memory first, production parity.

 


# Payment Processing System

**Initial Architecture & Requirements Document (v0.1)**

 


## 1. Purpose & Goals

Build a **single-tenant payment orchestration service** that:

  - 
Integrates with **Authorize.Net**

  - 
Exposes **clean REST APIs**

  - 
Uses an **explicit payment state machine**

  - 
Handles **webhooks authoritatively**

  - 
Guarantees **idempotency, immutability, and thread safety**

  - 
Supports **Purchase (Auth+Capture)** and **Auth/Capture (2-step)** flows

  - 
Starts **in-memory**, but is production-parity ready


 


## 2. Non-Goals (Out of Scope for v1)


  - 
Multi-tenancy

  - 
Multiple payment gateways (abstraction comes later)

  - 
Advanced subscription features beyond **basic recurring**

  - 
Persistent DB (in-memory only for now)

  - 
UI / Admin dashboard


 


## 3. High-Level Architecture


### 3.1 Components



 


### 3.2 Core Principles


  - 
**One Order → One Payment → One Transaction**

  - 
**Transactions are immutable**

  - 
**State transitions are explicit**

  - 
**Webhooks are authoritative**

  - 
**Every write API is idempotent**

  - 
**Thread-safe commits**


 


## 4. Domain Model (Conceptual)


### 4.1 Order


  - 
Business intent (e.g., purchase ₹100)

  - 
Created by merchant

  - 
Immutable after creation


### 4.2 Payment


  - 
Execution attempt for an order

  - 
One payment per order

  - 
Tracks lifecycle state


### 4.3 Transaction


  - 
Gateway-level transaction

  - 
Immutable record

  - 
New transaction created on retries

  - 
References original transaction if retry


 


## 5. Payment State Machine


### 5.1 States



### Failure States



### Notes


  - 
**Purchase flow**: CREATED → CAPTURED

  - 
**Auth/Capture flow**: CREATED → AUTHORIZED → CAPTURED

  - 
No backward transitions

  - 
Webhooks may move state forward


 


## 6. API Design Principles


  - 
**RESTful**

  - 
**Declarative**

  - 
**Idempotent**

  - 
**Business-intent driven**

  - 
No partial updates


 


## 7. REST API Contracts (High Level)


### 7.1 Create Order

`POST /orders`


  - 
Idempotent via `orderId`


 


### 7.2 Create Payment

`POST /payments`


  - 
One payment per order

  - 
Fails if payment already exists


 


### 7.3 Capture Payment

`POST /payments/{paymentId}/capture`

  - 
Only valid if state = AUTHORIZED

  - 
Idempotent


 


### 7.4 Get Payment

`GET /payments/{paymentId}`
Returns full state + transaction history.

 


## 8. Idempotency Strategy


  - 
**Idempotency-Key header**

  - 
One key per **business intent**

  - 
Stored in memory map (for now)

  - 
Replays return original response


 


## 9. Webhook Handling


### 9.1 Flow



### 9.2 Rules


  - 
Webhooks are **idempotent**

  - 
Webhooks can move state forward only

  - 
Webhooks validated before processing

  - 
Thread-safe updates


 


## 10. Retry Strategy


  - 
**Soft failures only**

  - 
New transaction created per retry

  - 
Original transaction ID referenced

  - 
New trace ID generated


 


## 11. Thread Safety & Consistency


  - 
All commit APIs synchronized / locked

  - 
Single writer per payment

  - 
No concurrent state transitions


 


## 12. Error Handling


### Categories


  - 
Validation errors (4xx)

  - 
State violations (409)

  - 
Gateway failures (502)

  - 
Internal errors (500)

Errors must include:

  - 
`traceId`

  - 
`paymentId` (if available)

  - 
`reasonCode`


 


## 13. Technology Stack

LayerChoiceLanguageJavaFrameworkSpring BootGateway SDKAuthorize.NetPersistenceIn-memory (ConcurrentHashMap)AsyncExecutor / QueueBuildMavenRuntimeDockerTestingJUnit + Mockito

 


## 14. Mocking Strategy


  - 
Auth.Net SDK fully mocked

  - 
Simulate:

    - 
Success

    - 
Soft failure

    - 
Hard failure

    - 
Delayed webhooks



 


## 15. Folder Structure (Proposed)



 


## 16. Development Phases


### Phase 1


  - 
In-memory models

  - 
Mocked Auth.Net

  - 
Payment creation + state machine

  - 
Webhooks via mock events


### Phase 2


  - 
Retry logic

  - 
Idempotency hardening

  - 
Better concurrency tests


### Phase 3


  - 
DB persistence

  - 
Gateway abstraction

  - 
Metrics & observability


 


## 17. Open Assumptions


  - 
Single tenant

  - 
Single currency per order

  - 
No partial captures (v1)

  - 
No refunds (v1)

  - 
Gateway webhooks are reliable but replayable


 


## 18. Explicit Ask for Cursor


Build this system **incrementally**, starting with:


  1. 
Domain models

  2. 
State machine

  3. 
In-memory repositories

  4. 
REST APIs

  5. 
Mocked gateway

  6. 
Webhook simulation


 

If you want next:

  - 
**Exact OpenAPI spec**

  - 
**State machine diagram (ASCII or Mermaid)**

  - 
**DB schema for Phase 3**

  - 
**Concurrency & locking strategy doc**

Just say which one.
***You***: Authorize.Net SDK needs to be integrated, 
This can be mocked for test cases only.
***ChatGPT***: Got it. Below is a **cleanly revised Architecture + Requirements document** with the **Authorize.Net SDK as a first-class integration**, and **mocking allowed only for tests**.

This version is **Cursor-ready** and removes any ambiguity about “mock first”.
You can directly replace the previous doc with this.

 


# Payment Processing System

**Initial Architecture & Requirements Document (v0.2)**

 


## 1. Purpose & Goals

Build a **single-tenant payment orchestration service** that:

  - 
**Directly integrates with Authorize.Net Java SDK**

  - 
Exposes **stable, declarative REST APIs**

  - 
Implements an **explicit payment state machine**

  - 
Treats **Authorize.Net webhooks as authoritative**

  - 
Guarantees **idempotency, immutability, and thread safety**

  - 
Supports:

    - 
**Purchase (Auth + Capture)**

    - 
**Authorize → Capture (2-step)**


  - 
Uses **in-memory persistence initially**, but remains **production-parity**


 


## 2. Explicit Non-Goals (v1)


  - 
Multi-tenant support

  - 
Multiple payment gateways

  - 
Refunds, voids, partial capture

  - 
Advanced subscription features (basic recurring only)

  - 
UI / Admin console


 


## 3. High-Level Architecture



 


## 4. Core Architecture Principles


  1. 
**Authorize.Net SDK is used in runtime**

  2. 
**Mocks are allowed ONLY in tests**

  3. 
**One Order → One Payment → One Transaction**

  4. 
**Transactions are immutable**

  5. 
**State transitions are explicit and forward-only**

  6. 
**Webhooks may advance state**

  7. 
**Every write API is idempotent**

  8. 
**All commits are thread-safe**


 


## 5. Gateway Integration Strategy


### 5.1 Authorize.Net SDK Usage


  - 
Use **official Authorize.Net Java SDK**

  - 
SDK is invoked from a **Gateway Adapter layer**

  - 
SDK configuration (keys, env) via Spring config

  - 
Sandbox mode for development

  - 
Production credentials injected at runtime


### 5.2 Gateway Adapter Interface



  - 
**Authorize.Net implementation is mandatory**

  - 
Future gateway abstraction is deferred


 


## 6. Mocking Policy (Very Important)


### Allowed

✅ Unit tests

✅ Integration tests

✅ Failure simulation

✅ Webhook replay testing

### Not Allowed

❌ Mocking gateway in production code

❌ Feature flags to bypass SDK

❌ Fake gateway implementations in runtime
**Rule:**

If the application is running (even locally), it uses the real Authorize.Net SDK.


 


## 7. Domain Model (Conceptual)


### 7.1 Order


  - 
Business intent

  - 
Immutable

  - 
Created by merchant


### 7.2 Payment


  - 
One per order

  - 
Tracks lifecycle

  - 
State machine controlled


### 7.3 Transaction


  - 
Gateway-level artifact

  - 
Immutable

  - 
New transaction per retry

  - 
References original transaction on retry


 


## 8. Payment State Machine


### States



### Terminal Failure States



### Rules


  - 
Purchase flow: `CREATED → CAPTURED`

  - 
Auth/Capture flow: `CREATED → AUTHORIZED → CAPTURED`

  - 
No backward transitions

  - 
Webhooks may move state forward

  - 
State change only via:

    - 
REST command

    - 
Verified webhook



 


## 9. REST API Contracts (High-Level)


### 9.1 Create Order

`POST /orders`

Idempotent on `orderId`

 


### 9.2 Create Payment

`POST /payments`


  - 
Triggers:

    - 
Purchase → SDK `purchase()`

    - 
Authorize → SDK `authorize()`



 


### 9.3 Capture Payment

`POST /payments/{paymentId}/capture`

  - 
Valid only if `AUTHORIZED`

  - 
Calls SDK `capture()`

  - 
Idempotent


 


### 9.4 Get Payment

`GET /payments/{paymentId}`
Returns:

  - 
Payment state

  - 
Transaction history

  - 
Gateway references


 


## 10. Idempotency Design


  - 
`Idempotency-Key` header required for all write APIs

  - 
One key per **business intent**

  - 
Stored in memory (map)

  - 
Replayed requests return original response

  - 
Thread-safe lookup + insert


 


## 11. Webhook Processing


### Flow



### Rules


  - 
Webhooks are authoritative

  - 
Webhooks are idempotent

  - 
Only forward state transitions allowed

  - 
Invalid transitions are ignored but logged


 


## 12. Retry Strategy


  - 
Retry only **soft failures**

  - 
Each retry:

    - 
Creates new transaction

    - 
Generates new traceId

    - 
References original transactionId


  - 
No mutation of past transactions


 


## 13. Thread Safety & Concurrency


  - 
Single-writer per payment

  - 
All state transitions synchronized

  - 
In-memory locking per paymentId

  - 
No concurrent commits


 


## 14. Error Handling

TypeHTTPValidation400State Conflict409Gateway Error502Internal Error500
Every error includes:

  - 
`traceId`

  - 
`paymentId` (if known)

  - 
`reasonCode`


 


## 15. Technology Stack

LayerChoiceLanguageJavaFrameworkSpring BootGatewayAuthorize.Net Java SDKStorageIn-memoryAsyncExecutor / Blocking QueueBuildMavenRuntimeDockerTestsJUnit + Mockito

 


## 16. Test Strategy


### Unit Tests


  - 
State machine

  - 
Idempotency

  - 
Validation


### Integration Tests


  - 
Mocked Authorize.Net SDK

  - 
Webhook replay

  - 
Failure simulation


### Manual / Local


  - 
Real SDK in sandbox mode


 


## 17. Folder Structure



 


## 18. Development Order (For Cursor)


  1. 
Domain models

  2. 
State machine

  3. 
Gateway adapter (real SDK)

  4. 
In-memory repositories

  5. 
REST APIs

  6. 
Webhook ingestion

  7. 
Test mocks for SDK


 


## 19. Final Explicit Instruction


**Authorize.Net SDK is mandatory in runtime.
Mocking is allowed ONLY for tests.
No fake gateway in production code.**


 

If you want next, I can generate:

  - 
✅ **Authorize.Net adapter skeleton code**

  - 
✅ **Exact OpenAPI spec**

  - 
✅ **Webhook validation contract**

  - 
✅ **State machine diagram (Mermaid)**

Just say which one.
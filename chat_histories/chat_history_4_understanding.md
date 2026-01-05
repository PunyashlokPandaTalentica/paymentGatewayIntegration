# Payment API types
_Exported on 03/01/2026 at 13:11:10 GMT+5:30 from Cursor (2.0.77)_

---

**User**

What are these APIis ? 



1. Purchase (auth + capture in one step).



2. Authorize + Capture (two-step).



3. Cancel (before capture).



4. Refunds (full + partial). 

---

**Cursor**

Reviewing the codebase to understand the current implementation and explain these payment APIs.


Here’s what these payment gateway APIs do:

## 1. **Purchase (Auth + Capture in one step)**

Single-step transaction that authorizes and captures funds immediately.

- **When to use**: Immediate payment (e.g., e-commerce checkout)
- **Flow**: Authorize → Capture (automatic)
- **Status**: Goes directly to `CAPTURED`/`SUCCESS`
- **In your codebase**: Implemented at `POST /v1/payments/{paymentId}/transactions/purchase` (line 31-60 in `TransactionController.java`)

## 2. **Authorize + Capture (two-step)**

Two-step process: first authorize (reserve funds), then capture later.

- **When to use**: Delayed fulfillment (e.g., pre-orders, shipping later)
- **Flow**:
  1. **Authorize**: Reserve funds → status `AUTHORIZED`
  2. **Capture**: Charge the reserved amount → status `CAPTURED`
- **In your codebase**: 
  - Authorize: `POST /v1/payments/{paymentId}/transactions/authorize` (line 62-91)
  - Capture: `POST /v1/payments/{paymentId}/transactions/{transactionId}/capture` (line 93-125)

## 3. **Cancel (before capture)**

Cancels an authorized transaction before it’s captured.

- **When to use**: Cancel an order before fulfillment
- **Flow**: Only works on `AUTHORIZED` transactions (not captured)
- **Status**: Moves to `CANCELLED`/`VOIDED`
- **In your codebase**: `VOID` transaction type exists (line 7 in `TransactionType.java`), but the controller endpoint isn’t implemented yet (marked as TODO in `PENDING.md`)

## 4. **Refunds (full + partial)**

Returns money to the customer after a successful capture.

- **When to use**: Customer returns item, dispute resolution, etc.
- **Types**:
  - **Full refund**: Returns the entire captured amount
  - **Partial refund**: Returns a portion of the captured amount
- **Flow**: Only works on `CAPTURED` transactions
- **In your codebase**: `REFUND` transaction type exists (line 8 in `TransactionType.java`), but the controller endpoint isn’t implemented yet (marked as TODO in `PENDING.md`)

---

**Current implementation status:**
- ✅ Purchase API — implemented
- ✅ Authorize API — implemented
- ✅ Capture API — implemented
- ❌ Cancel/Void API — not yet implemented
- ❌ Refund API — not yet implemented

The state machine (in `PaymentStateMachine.java`) already supports these transitions, so adding the cancel and refund endpoints should be straightforward.

---

**User**

What is order, payment and transaction 

---

**Cursor**

Reviewing the domain models to explain Order, Payment, and Transaction and their relationships.


Here’s what **Order**, **Payment**, and **Transaction** represent and how they relate:

## **Order** — Business intent

Represents the customer’s purchase intent.

**Fields:**
- `id` (UUID)
- `merchantOrderId` (unique identifier from merchant)
- `amount` (Money value object)
- `description`
- `customer` (email, phone)
- `status` (CREATED, PAYMENT_PENDING, PAYMENT_INITIATED, PAID, COMPLETED, FAILED, CANCELLED)
- `createdAt`, `updatedAt`

**Purpose:**
- Represents the business order
- Status is derived from Payment status
- One Order → One Payment (enforced)

**Example:**
```java
Order {
  id: "ord_123",
  merchantOrderId: "ORD-12345",
  amount: { amount: "1999.00", currency: "INR" },
  description: "Pro subscription",
  status: "CREATED"
}
```

---

## **Payment** — Payment lifecycle

Manages the payment process for an order.

**Fields:**
- `id` (UUID)
- `orderId` (FK to Order)
- `paymentType` (PURCHASE or AUTH_CAPTURE)
- `status` (INITIATED, AUTHORIZED, CAPTURED, FAILED, CANCELLED)
- `amount` (Money)
- `gateway` (AUTHORIZE_NET)
- `idempotencyKey` (for idempotency)
- `createdAt`, `updatedAt`

**Purpose:**
- Manages payment lifecycle
- Status is derived from Transaction history
- One Payment per Order
- Tracks which gateway and payment flow

**Example:**
```java
Payment {
  id: "pay_456",
  orderId: "ord_123",
  paymentType: "AUTH_CAPTURE",
  status: "AUTHORIZED",
  gateway: "AUTHORIZE_NET"
}
```

---

## **Transaction** — Immutable gateway interaction ledger

Immutable record of each gateway interaction.

**Fields:**
- `id` (UUID)
- `paymentId` (FK to Payment)
- `transactionType` (AUTH, CAPTURE, PURCHASE, VOID, REFUND)
- `transactionState` (REQUESTED, INITIATED, AUTHORIZED, SUCCESS, FAILED, PENDING, SETTLED, VOIDED)
- `gatewayTransactionId` (from gateway)
- `gatewayResponseCode`, `gatewayResponseMsg`
- `amount` (Money)
- `parentTransactionId` (for CAPTURE → AUTH, REFUND → CAPTURE)
- `traceId` (unique per attempt, new on retry)
- `createdAt`

**Purpose:**
- Immutable audit trail
- Gateway is source of truth
- One Payment can have multiple Transactions (e.g., AUTH → CAPTURE, or retries)
- No updates — new row for each attempt/retry

**Example:**
```java
// First transaction: Authorize
PaymentTransaction {
  id: "txn_789",
  paymentId: "pay_456",
  transactionType: "AUTH",
  transactionState: "AUTHORIZED",
  gatewayTransactionId: "authnet_txn_9876"
}

// Second transaction: Capture (references AUTH)
PaymentTransaction {
  id: "txn_790",
  paymentId: "pay_456",
  transactionType: "CAPTURE",
  transactionState: "SUCCESS",
  parentTransactionId: "txn_789"  // Links to AUTH
}
```

---

## **Relationship hierarchy**

```
Order (1) ──→ Payment (1) ──→ Transaction (1..n)
```

**Flow example:**
1. Create Order → `Order.status = CREATED`
2. Create Payment → `Payment.status = INITIATED`, `Order.status = PAYMENT_INITIATED`
3. Execute Purchase Transaction → `Transaction.type = PURCHASE`, `Transaction.state = SUCCESS`
4. Payment status derived → `Payment.status = CAPTURED`, `Order.status = PAID`

---

## **Key design principles**

1. Separation of concerns:
   - Order = business intent
   - Payment = payment lifecycle
   - Transaction = gateway interaction (authoritative)

2. Immutability:
   - Transactions are append-only
   - No updates — new row for retries/state changes

3. State derivation:
   - Order status ← derived from Payment status
   - Payment status ← derived from Transaction history
   - Transaction state ← authoritative (from gateway/webhooks)

4. One-to-one-to-many:
   - One Order → One Payment (enforced)
   - One Payment → Many Transactions (AUTH, CAPTURE, retries, etc.)

This design supports auditability, retries, and webhook reconciliation.

```java
Order {
  id: "ord_123",
  merchantOrderId: "ORD-12345",
  amount: { amount: "1999.00", currency: "INR" },
  description: "Pro subscription",
  status: "CREATED"
}
```

```java
Payment {
  id: "pay_456",
  orderId: "ord_123",
  paymentType: "AUTH_CAPTURE",
  status: "AUTHORIZED",
  gateway: "AUTHORIZE_NET"
}
```

```java
// First transaction: Authorize
PaymentTransaction {
  id: "txn_789",
  paymentId: "pay_456",
  transactionType: "AUTH",
  transactionState: "AUTHORIZED",
  gatewayTransactionId: "authnet_txn_9876"
}

// Second transaction: Capture (references AUTH)
PaymentTransaction {
  id: "txn_790",
  paymentId: "pay_456",
  transactionType: "CAPTURE",
  transactionState: "SUCCESS",
  parentTransactionId: "txn_789"  // Links to AUTH
}
```

```plaintext
Order (1) ──→ Payment (1) ──→ Transaction (1..n)
```

---

**User**

why the JPARrepository interfaces are not being used, I don't see any entries in DB. 

---

**Cursor**

Investigating why JPA repositories aren't being used and why there are no DB entries. Checking repository implementations and configuration.


Checking the JPA repository implementations and how they're configured:


Checking the repository interfaces to understand the mismatch:


## The Problem

There are two sets of repositories, and Spring is using the in-memory ones:

1. In-memory repositories (currently active):
   - `InMemoryOrderRepository` implements `OrderRepository` (domain model)
   - `InMemoryPaymentRepository` implements `PaymentRepository` (domain model)
   - `InMemoryPaymentTransactionRepository` implements `PaymentTransactionRepository` (domain model)
   - All annotated with `@Repository`

2. JPA repositories (not being used):
   - `JpaOrderRepository` extends `JpaRepository<OrderEntity, UUID>`
   - `JpaPaymentRepository` extends `JpaRepository<PaymentEntity, UUID>`
   - `JpaPaymentTransactionRepository` extends `JpaRepository<PaymentTransactionEntity, UUID>`
   - These work with Entity classes, not domain models

3. The service layer (`PaymentOrchestratorService`) injects domain model repository interfaces (`OrderRepository`, `PaymentRepository`, etc.), so Spring selects the in-memory implementations.

## Why JPA Repositories Aren't Used

- They operate on entities (`OrderEntity`, `PaymentEntity`), not domain models (`Order`, `Payment`)
- The service layer expects domain model repositories
- There’s no adapter/mapper between entities and domain models
- Both sets are `@Repository`, and Spring picks the in-memory ones

## Solutions

### Option 1: Disable In-Memory Repositories (Recommended)

Disable the in-memory implementations when using a database:

```java
// In InMemoryOrderRepository.java
@Repository
@Profile("!jpa")  // Only active when 'jpa' profile is NOT active
public class InMemoryOrderRepository implements OrderRepository {
    // ... existing code
}
```

Do the same for `InMemoryPaymentRepository`, `InMemoryPaymentTransactionRepository`, and `InMemoryWebhookRepository`.

Then create JPA-based implementations of the domain model repository interfaces:

```java
@Repository
@Profile("jpa")
public class JpaOrderRepositoryAdapter implements OrderRepository {
    private final JpaOrderRepository jpaRepository;
    private final OrderMapper mapper; // You'll need a mapper
    
    // Implement all methods, converting between Entity and Domain Model
}
```

### Option 2: Use @Primary on JPA Implementations

Mark JPA-based implementations as `@Primary`:

```java
@Repository
@Primary
public class JpaOrderRepositoryAdapter implements OrderRepository {
    // JPA-based implementation
}
```

### Option 3: Conditional Configuration

Exclude in-memory repositories when a datasource is present:

```java
@Repository
@ConditionalOnMissingBean(name = "dataSource")
public class InMemoryOrderRepository implements OrderRepository {
    // ...
}
```

## Quick Fix (Immediate)

To switch to JPA now, disable the in-memory repositories:

1. Remove or comment out `@Repository` from all `InMemory*Repository` classes
2. Create adapter classes that implement the domain model repository interfaces using JPA repositories internally
3. Add mappers to convert between `Entity` classes and domain `Model` classes

Should I create the JPA adapter implementations with entity-to-model mappers?

```java
// In InMemoryOrderRepository.java
@Repository
@Profile("!jpa")  // Only active when 'jpa' profile is NOT active
public class InMemoryOrderRepository implements OrderRepository {
    // ... existing code
}
```

```java
@Repository
@Profile("jpa")
public class JpaOrderRepositoryAdapter implements OrderRepository {
    private final JpaOrderRepository jpaRepository;
    private final OrderMapper mapper; // You'll need a mapper
    
    // Implement all methods, converting between Entity and Domain Model
}
```

```java
@Repository
@Primary
public class JpaOrderRepositoryAdapter implements OrderRepository {
    // JPA-based implementation
}
```

```java
@Repository
@ConditionalOnMissingBean(name = "dataSource")
public class InMemoryOrderRepository implements OrderRepository {
    // ...
}
```

---

**User**

why is it the implementation like this now

---

**Cursor**

Checking project history and requirements to understand why this implementation was chosen.


## Why the current implementation

### 1. Initial design decision
From `requirements.md` (line 25):
> "Uses in-memory persistence initially, but remains production-parity"

The project started with in-memory storage to:
- Speed up development
- Avoid database setup during early development
- Keep the architecture ready for database migration

### 2. Migration in progress
From `TODO/PENDING.md` (lines 13, 37-41):
```markdown
- [ ] Replace in-memory repositories with JPA repositories in services (IN PROGRESS)
- [ ] Create entity-to-domain model mappers
- [ ] Add @Transactional to service methods
- [ ] Test database integration end-to-end
```

### 3. What’s done
From `COMPLETED.md` (Milestone 7):
- PostgreSQL dependencies added
- JPA entity classes created (`OrderEntity`, `PaymentEntity`, etc.)
- JPA repository interfaces created (`JpaOrderRepository`, `JpaPaymentRepository`, etc.)
- Flyway migrations created
- Database configuration in `application.yml`

### 4. What’s missing
The final step: replacing in-memory repositories with JPA-based implementations.

Current state:
- In-memory repositories are active (`@Repository` on `InMemory*Repository` classes)
- JPA repositories exist but aren’t wired into services
- No mappers between `Entity` classes and domain `Model` classes
- Services still inject domain model repository interfaces, which resolve to in-memory implementations

## The architecture gap

```
Service Layer
    ↓ (injects)
Domain Model Repository Interface (OrderRepository, PaymentRepository)
    ↓ (currently resolves to)
InMemory*Repository (active) ← Currently being used
    ↓ (should resolve to)
JPA*RepositoryAdapter (not created yet) → Jpa*Repository → Database
```

## Why it’s structured this way

1. Separation of concerns: domain models (`Order`, `Payment`) vs. persistence entities (`OrderEntity`, `PaymentEntity`)
2. Testability: in-memory repositories for fast unit tests
3. Migration path: infrastructure is ready, but the switch hasn’t been made

## Next steps to complete the migration

1. Create adapter classes that implement domain model repository interfaces using JPA repositories
2. Add mappers to convert between `Entity` ↔ `Model`
3. Disable in-memory repositories (remove `@Repository` or use `@Profile`)
4. Add `@Transactional` to service methods
5. Test end-to-end

The JPA infrastructure is in place; the final wiring step remains. Should I help create the adapter implementations and mappers to complete the migration?

```markdown
- [ ] Replace in-memory repositories with JPA repositories in services (IN PROGRESS)
- [ ] Create entity-to-domain model mappers
- [ ] Add @Transactional to service methods
- [ ] Test database integration end-to-end
```

```plaintext
Service Layer
    ↓ (injects)
Domain Model Repository Interface (OrderRepository, PaymentRepository)
    ↓ (currently resolves to)
InMemory*Repository (active) ← Currently being used
    ↓ (should resolve to)
JPA*RepositoryAdapter (not created yet) → Jpa*Repository → Database
```

---

**User**

@bash (92-146) 



Whats the circular dependency here

---

**Cursor**

Investigating the circular dependency. Checking `JpaPaymentRepositoryImpl` and related classes.


Checking the PaymentMapper and related mappers for circular dependencies:


Checking for multiple PaymentRepository implementations and their conditions:


Checking the full `JpaPaymentRepositoryImpl` implementation, especially `updateWithLock`:


## The Circular Dependency Problem

The error shows:
```
jpaPaymentRepositoryImpl → (trying to inject) → jpaPaymentRepositoryImpl (itself!)
```

**The cycle:**
1. `JpaPaymentRepositoryImpl` needs `JpaPaymentRepository` (Spring Data JPA interface)
2. Spring creates a proxy for `JpaPaymentRepository`
3. During proxy creation, Spring may resolve beans that depend on `PaymentRepository`
4. `PaymentRepository` resolves to `JpaPaymentRepositoryImpl` (which is still being created)
5. Circular dependency

## The Root Cause

`JpaPaymentRepositoryImpl` uses `@RequiredArgsConstructor`, which injects:
- `JpaPaymentRepository jpaRepository` (Spring Data JPA proxy)
- `PaymentMapper mapper`

When Spring creates the `JpaPaymentRepository` proxy, it may trigger initialization that references `PaymentRepository`, which points back to `JpaPaymentRepositoryImpl` while it's still being constructed.

## Solution

Use `@Lazy` on the `JpaPaymentRepository` dependency to break the cycle:

```java
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentRepositoryImpl implements PaymentRepository {

    private final JpaPaymentRepository jpaRepository;  // This needs @Lazy
    private final PaymentMapper mapper;

    // ... rest of the code
}
```

**Fix:**

```java
@Repository
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentRepositoryImpl implements PaymentRepository {

    private final JpaPaymentRepository jpaRepository;
    private final PaymentMapper mapper;

    // Use constructor injection with @Lazy
    public JpaPaymentRepositoryImpl(
            @Lazy JpaPaymentRepository jpaRepository,
            PaymentMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }
    
    // ... rest of the methods
}
```

Or keep `@RequiredArgsConstructor` and add `@Lazy` to the field:

```java
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentRepositoryImpl implements PaymentRepository {

    @Lazy
    private final JpaPaymentRepository jpaRepository;
    private final PaymentMapper mapper;
    
    // ... rest of the code
}
```

**Note:** `@Lazy` on a `@RequiredArgsConstructor` field may not work as expected. Prefer explicit constructor injection with `@Lazy` on the parameter.

This defers the creation of the `JpaPaymentRepository` proxy until it's first used, breaking the cycle.

```plaintext
jpaPaymentRepositoryImpl → (trying to inject) → jpaPaymentRepositoryImpl (itself!)
```

```java
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentRepositoryImpl implements PaymentRepository {

    private final JpaPaymentRepository jpaRepository;  // This needs @Lazy
    private final PaymentMapper mapper;

    // ... rest of the code
}
```

```java
@Repository
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentRepositoryImpl implements PaymentRepository {

    private final JpaPaymentRepository jpaRepository;
    private final PaymentMapper mapper;

    // Use constructor injection with @Lazy
    public JpaPaymentRepositoryImpl(
            @Lazy JpaPaymentRepository jpaRepository,
            PaymentMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }
    
    // ... rest of the methods
}
```

```java
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentRepositoryImpl implements PaymentRepository {

    @Lazy
    private final JpaPaymentRepository jpaRepository;
    private final PaymentMapper mapper;
    
    // ... rest of the code
}
```

---

**User**

simplify, will autowiring fix it

---

**Cursor**

Checking the current implementation:


Yes. Using `@Autowired` on fields instead of constructor injection can fix this. Field injection happens after the bean is created, which can break the cycle.

**Simple fix - replace `@RequiredArgsConstructor` with `@Autowired`:**

```java
@Repository
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentRepositoryImpl implements PaymentRepository {

    @Autowired
    private JpaPaymentRepository jpaRepository;
    
    @Autowired
    private PaymentMapper mapper;

    // Remove @RequiredArgsConstructor and make fields non-final
    // Rest of the code stays the same...
}
```

**Complete fix:**

```java
package com.paymentgateway.repository.impl;

import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.domain.entity.PaymentEntity;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.jpa.JpaPaymentRepository;
import com.paymentgateway.repository.mapper.PaymentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Repository
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentRepositoryImpl implements PaymentRepository {

    @Autowired
    private JpaPaymentRepository jpaRepository;
    
    @Autowired
    private PaymentMapper mapper;

    // ... rest of the methods stay the same
}
```

**Why this works:**
- Constructor injection requires dependencies at creation time
- Field injection happens after instantiation, allowing Spring to create the bean first, then inject dependencies

This should resolve the circular dependency. Apply the same pattern to other JPA repository implementations if they have the same issue.

```java
@Repository
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentRepositoryImpl implements PaymentRepository {

    @Autowired
    private JpaPaymentRepository jpaRepository;
    
    @Autowired
    private PaymentMapper mapper;

    // Remove @RequiredArgsConstructor and make fields non-final
    // Rest of the code stays the same...
}
```

```java
package com.paymentgateway.repository.impl;

import com.paymentgateway.domain.model.Payment;
import com.paymentgateway.domain.entity.PaymentEntity;
import com.paymentgateway.repository.PaymentRepository;
import com.paymentgateway.repository.jpa.JpaPaymentRepository;
import com.paymentgateway.repository.mapper.PaymentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Repository
@ConditionalOnProperty(name = "app.repository.type", havingValue = "jpa", matchIfMissing = true)
public class JpaPaymentRepositoryImpl implements PaymentRepository {

    @Autowired
    private JpaPaymentRepository jpaRepository;
    
    @Autowired
    private PaymentMapper mapper;

    // ... rest of the methods stay the same
}
```


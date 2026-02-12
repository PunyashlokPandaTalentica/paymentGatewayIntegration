***You***: Draw a payment state machine
***ChatGPT***: Below is a **clear, production-grade payment state machine**, aligned with what you’re designing (Auth.Net–style gateway, immutable transactions, retries, idempotency, webhooks).

 


## Payment State Machine (High-Level)

4

### ASCII State Diagram (Authoritative)



 


## States Explained (Precise Semantics)


### 1. **CREATED**


  - 
Order exists

  - 
No gateway interaction yet

  - 
Idempotency key created (business intent scoped)


 


### 2. **PENDING**


  - 
Payment request sent to gateway

  - 
Awaiting synchronous response or webhook

  - 
**Thread-safe & idempotent**


 


### 3. **AUTHORIZED** (optional flow)


  - 
Funds reserved, not captured

  - 
Valid for delayed capture

  - 
Expirable (gateway-driven)


 


### 4. **CAPTURED**


  - 
Funds captured successfully

  - 
Still subject to settlement

  - 
Immutable transaction record


 


### 5. **SETTLED** ✅ (Terminal – Success)


  - 
Funds settled to merchant account

  - 
Webhook-driven transition

  - 
Final, no further transitions


 


### 6. **FAILED** ❌ (Terminal – Failure)


  - 
Hard failures (invalid card, insufficient funds)

  - 
No retries allowed

  - 
New transaction required


 


### 7. **CANCELLED** (Terminal – Neutral)


  - 
User cancelled

  - 
Auth expired

  - 
System aborted before capture


 


## Retry & Soft Failure Extension


**Rules**

  - 
Retry only from `PENDING`

  - 
Generate **new traceId**, reference original transactionId

  - 
Enforce retry limits


 


## Invariants (Very Important)


  - 
**One order → one payment**

  - 
**One payment → one immutable transaction**

  - 
State transitions are **append-only**

  - 
Webhooks are **authoritative**

  - 
All transitions are:

    - 
Idempotent

    - 
Thread-safe

    - 
Deterministic



 


## If You Want Next

I can:

  - 
Convert this into a **Spring StateMachine config**

  - 
Generate a **PlantUML diagram**

  - 
Map this to **Authorize.Net APIs**

  - 
Add **subscription / recurring flow**

  - 
Add **refund & chargeback sub-state machines**

Just tell me which layer you want next.
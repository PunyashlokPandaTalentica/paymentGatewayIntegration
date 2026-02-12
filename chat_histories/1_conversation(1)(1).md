***You***: Define exact REST contracts
***ChatGPT***: Below is a **precise, implementation-ready REST contract** for the **Payment Processing system**, aligned with your earlier constraints:

  - 
**Immutable transactions**

  - 
**One order → one payment → one transaction**

  - 
**Auth+Capture (single step) OR Auth then Capture (two-step)**

  - 
**Idempotent, thread-safe**

  - 
**Authoritative webhooks**

  - 
**Retry soft failures**

  - 
**New trace per retry, reference original transaction**

  - 
**In-memory for now**

  - 
**Authorize.Net as gateway (SDK mocked initially)**


 


# 1. Core Resource Model


### Resources


Relationships:


 


# 2. Common Conventions (Applies to ALL APIs)


## Headers



## Idempotency Rules


  - 
Same `(Idempotency-Key + endpoint + body-hash)` → **same response**

  - 
Conflicting payload → **409 IDENTITY_CONFLICT**


## Money Representation



## Timestamps


  - 
RFC3339 UTC (`2025-01-30T18:42:21Z`)


 


# 3. Order APIs


## 3.1 Create Order


### `POST /v1/orders`


#### Request



#### Response `201`



#### Order States



 


# 4. Payment APIs


## 4.1 Create Payment Intent


### `POST /v1/orders/{orderId}/payments`


#### Request



#### Response `201`



 


# 5. Transaction APIs (Immutable)


## 5.1 Commit Purchase (Auth + Capture)


### `POST /v1/payments/{paymentId}/transactions/purchase`


**Thread-safe, idempotent**


#### Request



#### Response `201`



 


## 5.2 Authorize Only


### `POST /v1/payments/{paymentId}/transactions/authorize`


#### Response



 


## 5.3 Capture Authorized Payment


### `POST /v1/payments/{paymentId}/transactions/{transactionId}/capture`


#### Request



#### Response



 


# 6. Retry Semantics (Soft Failures)


### Rule


  - 
**Retry creates a NEW transaction**

  - 
**References original transaction**


#### Example



 


# 7. Query APIs


## 7.1 Get Order


### `GET /v1/orders/{orderId}`



 


## 7.2 Get Transactions for Payment


### `GET /v1/payments/{paymentId}/transactions`



 


# 8. Webhook Contracts (Authoritative)


## 8.1 Webhook Payload


### `POST /v1/webhooks/authorize-net`



### Processing Rules


  - 
Verify signature

  - 
Idempotent on `eventId`

  - 
Enqueue → async processor

  - 
Can transition internal state **even if API failed earlier**


 


# 9. Error Model (Unified)



 


# 10. Status Enumerations


### Transaction Status



### Payment Status



 


# 11. What This Design Guarantees

✔ Fully RESTful

✔ Business-intent idempotency

✔ Immutable transaction ledger

✔ Safe retries

✔ Gateway-authoritative reconciliation

✔ Spring Boot friendly

✔ Easy gateway abstraction later

 

If you want next:

  - 
**State machine diagram**

  - 
**DB schema**

  - 
**Idempotency implementation details**

  - 
**Authorize.Net mapping**

  - 
**Webhook reconciliation algorithm**

Just say which one.
# API Command Boundaries

Date: 2026-06-15

OrderPilot command APIs must preserve this authority boundary:

```text
frontend / bot / connector / AI worker
-> Core API controller
-> tenant + actor context
-> idempotency/request-integrity guard
-> typed application service
-> deterministic business validation
-> transaction + audit/outbox where applicable
```

For OP-CAP-17C, quote mutation controllers remain thin HTTP adapters. They resolve trusted request context, apply the idempotency guard, and delegate business behavior to existing quote services. They do not own quote approval policy, validation rules, product/customer/pricing truth, connector execution, or audit semantics.

Business DTOs should not carry idempotency keys when the caller can send the `Idempotency-Key` header. Existing DTO fields may remain temporarily for compatibility, but new UI callers should treat idempotency as request metadata and keep business JSON focused on business intent.

Internal IDs remain allowed only where the existing workflow requires a safe application resource identifier for navigation or command targeting. Raw payloads, connector secrets, storage keys, customer message bodies, and authority fields must not be returned to the dashboard.

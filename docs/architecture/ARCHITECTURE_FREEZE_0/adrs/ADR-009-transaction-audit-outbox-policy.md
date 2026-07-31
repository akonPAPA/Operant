# ADR-009 — Transaction / audit / outbox policy

- **Status:** Proposed. Source commit `c11f7e8`.
- **Problem:** External effects must never corrupt business state; audit must never diverge from truth.
- **Current evidence:** `OutboxEvent` (`PENDING → PUBLISHED_INTERNAL_ONLY | SKIPPED_EXTERNAL_DISABLED`);
  `AuditEvent` append-only; `IdempotencyService`/`IdempotencyRecord`; `ConnectorIdempotencyService`.
- **Options:** (a) publish external effect inside business tx; (b) transactional outbox + separate relay,
  at-least-once + idempotent effect.
- **Decision:** (b). Business mutation + AuditEvent + OutboxEvent commit in **one local tx**; a separate
  relay publishes at-least-once; effects are idempotent. **No exactly-once external processing claim.**
  Accepted audit can never commit independently of a rolled-back business state.
- **Consequences:** consistent business state under provider failure; duplicates absorbed by idempotency.
- **Rejected alternatives:** publishing inside the business tx (external latency/failure corrupts tx);
  exactly-once claims (unachievable across a network boundary).
- **Migration impact:** relay activation is a State-2 step (P1-F); today external is disabled.
- **Security impact:** positive — no partial external effects; audit integrity guaranteed.
- **Performance impact:** positive — business tx short; effects async.
- **Reversibility:** high (relay is additive).
- **NOT_PROVEN:** external publish path; retry/DLQ semantics live behavior.

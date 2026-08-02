# ADR-004 — PostgreSQL schema strategy

- **Status:** Proposed. Source commit `c11f7e8`.
- **Problem:** How to partition data as modules formalize — one DB, schema-per-module, or DB-per-module?
- **Current evidence:** 69 Flyway migrations (V1–V69), one PostgreSQL, tenant_id on business rows,
  transactional outbox + audit atomic with business writes.
- **Options:** (a) one DB, logical module ownership; (b) schema-per-module same DB; (c) DB-per-module.
- **Decision:** (a) now — **one physical PostgreSQL, one authoritative transactional DB**, logical
  ownership via module-owned repositories + naming. Cross-module writes only via owner API; one tx may span
  modules only when one invariant requires it (accept-quote writes Quote+Audit+Outbox atomically). Future
  DB-extraction seams documented (View 15), not implemented.
- **Consequences:** preserves atomic invariants; simplest ops; extraction remains possible later.
- **Rejected alternatives:** DB-per-small-module and per-service-group instance (no measured need,
  cross-DB transactions, Saga where one local tx suffices) — all explicit quality-gate failures.
- **Migration impact:** none to physical schema now; naming/ownership conventions in Wave 0–2.
- **Security impact:** neutral/positive (tenant constraints centralized).
- **Performance impact:** positive (local joins, no distributed tx).
- **Reversibility:** high.
- **NOT_PROVEN:** future extraction cost/benefit; per-module schema namespacing not yet applied.

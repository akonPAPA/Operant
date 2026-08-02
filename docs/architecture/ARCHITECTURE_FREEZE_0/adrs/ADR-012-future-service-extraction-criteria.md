# ADR-012 — Future service-extraction criteria

- **Status:** Proposed. Source commit `c11f7e8`.
- **Problem:** When (if ever) may a module become a separate deployable service?
- **Current evidence:** business core is one monolith; AI/OCR worker and (target) Connector Gateway/agent
  are already separate runtimes with independent failure/scaling profiles.
- **Options:** (a) extract by intuition; (b) extract only when a 12-point gate is fully satisfied.
- **Decision:** (b). A module qualifies as a service seam only when ALL hold: (1) one clear owner; (2)
  stable versioned contract; (3) independent scaling profile; (4) independent failure domain; (5)
  independent deployment reason; (6) no required shared ACID transaction; (7) defined eventual-consistency
  model; (8) idempotency/duplicate behavior; (9) reconciliation/compensation; (10) separate data ownership;
  (11) observability/SLO; (12) evidence extraction lowers total risk or cost. Otherwise it stays a module.
- **Consequences:** no premature microservices; `auditoutbox` explicitly never extractable (atomic invariant).
- **Rejected alternatives:** extraction for appearance (Kafka/K8s-for-appearance, distributed locks without
  need) — explicit quality-gate failures.
- **Migration impact:** none now; View 15 lists candidate seams only (dashed, not current services).
- **Security impact:** positive — avoids new network trust boundaries without need.
- **Performance impact:** avoids distributed-transaction overhead.
- **Reversibility:** n/a (nothing extracted).
- **NOT_PROVEN:** that any candidate seam currently satisfies all 12 criteria.

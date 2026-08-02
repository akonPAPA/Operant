# ADR-001 — Modular-monolith boundaries

- **Status:** Proposed (Architecture Freeze 0). Source commit `c11f7e8`.
- **Problem:** 969 core-api files span 28 domain contexts, but boundaries are not enforced; `workspace`
  (34) mixes quote/order/review, `trust` (86) mixes AI/risk/analytics/events. Cross-context coupling is
  possible today.
- **Current evidence:** `ApiRouteSecurityPolicy` (healthy shared authority); `LifecycleTerminalOwnerArchitectureTest`
  (one architecture test exists); no Spring Modulith / ArchUnit boundary enforcement yet.
- **Options:** (a) leave implicit; (b) enforce logical module boundaries inside one deployable; (c) extract
  microservices now.
- **Decision:** (b) — strict modular monolith at the business core. 30 target modules in `<module>.api` /
  `<module>.internal` structure; other modules depend only on `.api`; no cross-module repository/entity access.
- **Consequences:** enforceable boundaries; readable ownership; future extraction cheap. Requires a
  packaging pass (Waves 0–6).
- **Rejected alternatives:** microservice extraction now (no proven failure/scaling boundary — ADR-012);
  status quo (drift continues).
- **Migration impact:** Wave 0 adds markers/tests with no behavior change; later waves repackage.
- **Security impact:** positive — narrows blast radius, preserves single authority source.
- **Performance impact:** neutral (in-process calls).
- **Reversibility:** high (packaging, not deployment).
- **NOT_PROVEN:** module count is a design target; exact package moves not executed in this freeze.

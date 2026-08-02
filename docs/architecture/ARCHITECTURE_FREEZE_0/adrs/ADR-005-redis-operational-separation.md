# ADR-005 — Redis operational separation

- **Status:** Proposed. Source commit `c11f7e8`.
- **Problem:** How to allocate Redis without per-module sprawl.
- **Current evidence:** `RedisRateCounter`/`LettuceRedisRateCounter`/`RuntimeRateRedisConfiguration`
  (rate/runtime), `RedisGatewayHeaderReplayAdmissionStore` (replay/security), BFF session `REDIS_REQUIRED`,
  control-plane replay namespace.
- **Options:** (a) one Redis for everything; (b) Redis partitioned by operational semantics; (c) Redis per
  business module.
- **Decision:** (b) — two operational roles: **session/security** (session, gateway+control replay) and
  **runtime/rate/cache** (rate limiting, runtime admission, short-lived cache, provider circuit state).
  Redis is never the sole source of truth for business state. Physical split of the two roles is justified
  but ops-budget-gated (owner decision).
- **Consequences:** clear blast radii; correctness never depends on Redis persistence.
- **Rejected alternatives:** Redis-per-module (no availability/eviction/security differences to justify);
  single Redis for all (couples session security to rate-limit traffic blast radius).
- **Migration impact:** logical separation now; physical split when budget approved.
- **Security impact:** positive — session/replay isolated from runtime traffic.
- **Performance impact:** positive under load (independent scaling).
- **Reversibility:** high.
- **NOT_PROVEN:** deployed Redis failover/expiry (execution-state NOT_PROVEN list); physical split.

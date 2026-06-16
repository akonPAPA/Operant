# OP-CAP-16K — Runtime Governance Production Hardening Pack

## What 16K hardens

A single bounded production-hardening stage covering three workstreams over the runtime governance
control plane built in 16C–16J:

1. **Signed/verified actor** for runtime entitlement admin mutations (stronger than a trusted header).
2. **Runtime plan DB integrity** — a Postgres integration test for the V43 invariant, and corrected
   service-level bounded-window overlap conflict logic.
3. **Distributed runtime rate limiting** — a Redis-backed `RateLimitStore`, selectable by config,
   with the in-memory store preserved as the default fallback.

No new stage numbers are created; unresolved items are listed as future candidates below.

## Terminology clarifications (as applied)

- **Trusted header** — gateway/edge transport context (`X-Tenant-Id`, `X-OrderPilot-Permissions`,
  `X-OrderPilot-Actor-Id`). Carries context but is **not** authorization. Mutations still require
  tenant scoping, `RUNTIME_ENTITLEMENT_MANAGE`, service validation, audit, DB invariants, and the
  runtime guard where applicable.
- **Verified/signed actor** — stronger than a trusted header: when a signing secret is configured the
  actor header must carry a valid HMAC signature. This is a narrow control-plane hardening, **not** a
  full Spring Security/OIDC principal.
- **DB invariant** — a rule enforced by the database itself (V43 partial unique index), independent of
  Java service code, so races/bugs/manual SQL cannot create impossible states.
- **Open-ended ACTIVE plan** — `status = ACTIVE` and `effective_until IS NULL`: the current indefinite
  plan. V43 forbids two of these per tenant. Bounded overlap is handled at the service level.
- **H2 vs Postgres** — H2 tests run with Flyway **disabled** (Hibernate `create-drop`), so V43 is not
  executed under H2. A Postgres-profile integration test proves it at the real DB level.
- **Redis rate store** — Redis is **only** a distributed counter for runtime rate limiting. It is not
  authorization, entitlement, quota, billing, audit, business state, AI output, or permissions.

## 16J limitations addressed

| 16J limitation | 16K resolution |
|----------------|----------------|
| Actor is only a trusted gateway header, not signed/verified | `SignedActorVerifier` + signed-mode in `RequestActorResolver` (HMAC over `tenantId\nactorId\ntimestamp`) |
| Trusted-header meaning not explicit in code/docs | Documented here + javadoc on resolver/verifier; trusted vs verified vs fallback made explicit |
| DB invariant covers only open-ended ACTIVE plans | V43 unchanged (still the right narrow DB invariant); bounded overlap now enforced at service level + tested; DB-level bounded-overlap exclusion evaluated and **deferred** (see below) |
| Overlapping bounded ACTIVE windows were service-level only | Generalized service overlap logic (`rangesOverlap`) with a full test matrix |
| V43 not exercised under H2 / no Postgres test | `RuntimePlanInvariantPostgresIntegrationTest` added (Postgres profile) |
| Rate limiting in-memory only, not multi-node safe | `RedisRateLimitStore` behind `RateLimitStore`, selectable by config; in-memory remains default |

## Signed actor model

- Headers: `X-OrderPilot-Actor-Id`, `X-OrderPilot-Actor-Signature`, `X-OrderPilot-Actor-Timestamp`.
- Canonical string: `tenantId + "\n" + actorId + "\n" + timestamp`.
- HMAC-SHA-256, lowercase hex, **constant-time** comparison (`MessageDigest.isEqual`).
- Property: `orderpilot.security.actor-signing-secret` (empty = not configured).
- Freshness: `orderpilot.security.actor-signature-max-skew-seconds` (default 300).
- The body `actorId` field was already removed in 16J and remains absent/ignored; the audit actor is
  always the verified/resolved actor.

### Behavior with secret configured (production)

- A mutation must present a valid signature + fresh timestamp for the actor over the current tenant.
- Missing signature/timestamp, invalid signature, stale or malformed timestamp → **401**
  (`ACTOR_VERIFICATION_FAILED`); a missing actor header → 401.
- A malformed actor UUID → **400**.
- `RUNTIME_ENTITLEMENT_MANAGE` is still required (a valid signature does not grant permission); reads
  still require `RUNTIME_ENTITLEMENT_READ` and need no signature.

### Behavior with secret absent (local/dev/test)

- The 16J trusted-header fallback is preserved exactly: actor header present → that actor; absent →
  stable `SYSTEM_ACTOR` sentinel; malformed UUID → 400. No signature is required.

### Actor audit behavior

Every runtime entitlement admin mutation audits the **resolved/verified** actor (never a body value),
via the existing `AuditEventService`. No signature or secret is ever logged or returned in errors.

## V43 Postgres verification status

- V43 (`uq_tenant_runtime_plan_active_open`, partial unique on `(tenant_id) WHERE status='ACTIVE' AND
  effective_until IS NULL`) is unchanged.
- `RuntimePlanInvariantPostgresIntegrationTest` (profile `integration-test`, Flyway-on Postgres)
  proves at the real DB level: first open-ended ACTIVE succeeds; a second for the same tenant fails
  with `DataIntegrityViolationException`; a closed historical ACTIVE plan does not violate; a
  SUSPENDED open-ended plan does not violate; a different tenant does not violate.
- This test requires a running Postgres (same convention as the other `*PostgresIntegrationTest`
  classes); it does not run in the normal H2 suite. Run via the project's integration-test profile,
  e.g. `mvn -Dtest=RuntimePlanInvariantPostgresIntegrationTest test` with `SPRING_DATASOURCE_*`/local
  Postgres available.

## Bounded-window DB constraint decision

**Deferred (service-level enforced + tested).** A DB-level bounded-overlap rule would need a
`btree_gist` exclusion constraint on `tstzrange(effective_from, COALESCE(effective_until,'infinity'))`
with `tenant_id` equality and `WHERE status='ACTIVE'`. The repo does not currently enable the
`btree_gist` extension, and adding an extension + exclusion constraint is broader and riskier than
this hardening stage warrants (extension availability varies by environment; migration must be safe
against existing data). It is therefore deferred. Bounded-window overlap is enforced at the service
level (`requireNoActiveWindowOverlap` / `rangesOverlap`) and covered by a full test matrix:
overlapping → conflict; adjacent → allowed; open-ended overlaps later ACTIVE; new open-ended overlaps
existing future bounded ACTIVE; SUSPENDED/EXPIRED/DISABLED never conflict; different tenant never
conflicts.

## Service-level overlap rules

Two ACTIVE plans for a tenant conflict when their half-open windows `[effective_from, effective_until)`
overlap (null `effective_until` = `+infinity`). Checked on `createPlan` (when the new plan is ACTIVE)
and `updatePlan` (when the resulting plan is ACTIVE, excluding the plan itself), via a single
tenant-scoped, index-backed query. Non-ACTIVE plans are ignored.

## Redis rate-store architecture

- `RateLimitStore.addAndGet(key, windowStart, windowSeconds, weight)` — `windowSeconds` added so a
  distributed store can set a TTL (the in-memory store ignores it; behavior unchanged from 16C).
- `RedisRateLimitStore` builds keys, normalizes identifiers, and applies the fail mode; it delegates
  the atomic operation to a tiny `RedisRateCounter` (`LettuceRedisRateCounter` in production), which
  keeps the Redis client out of the store's logic and makes the store unit-testable without Redis.
- Selection is deterministic by config: the in-memory default bean is gated to
  `orderpilot.runtime.rate.store=in-memory` (default), and `RuntimeRateRedisConfiguration` provides
  the Redis store only when `=redis`. Spring Boot's `RedisAutoConfiguration` is excluded so **no Redis
  connection is created unless the distributed store is explicitly selected** (the actuator Redis
  health indicator also stays inactive).

### Config

| Property | Default | Meaning |
|----------|---------|---------|
| `orderpilot.runtime.rate.store` | `in-memory` | `in-memory` or `redis` |
| `orderpilot.runtime.rate.redis.host` | `localhost` | Redis host (redis mode) |
| `orderpilot.runtime.rate.redis.port` | `6379` | Redis port (redis mode) |
| `orderpilot.runtime.rate.redis.key-prefix` | `orderpilot:runtime-rate` | key prefix |
| `orderpilot.runtime.rate.redis.fail-open` | `false` | failure mode (see below) |

### Key format

`{prefix}:{tenantId}:{operation}:{windowStartEpoch}` — built only from a UUID and an enum name; a
defensive whitelist replaces any unexpected character. TTL = `windowSeconds + 5s` buffer.

### Atomicity model

A single Lua script does `INCRBY` then sets `EXPIRE` only on the first write of the window (when the
returned value equals the just-added delta). This removes the INCR-succeeds-but-EXPIRE-missing race a
separate INCR + EXPIRE would have.

### Failure mode

Explicit and configurable. **Fail-closed by default**: if Redis is unreachable the store returns a
saturating value so the guard denies with a stable rate-limit error (429) — no raw Redis exception
reaches the API. **Fail-open** (`fail-open=true`) returns just this request's weight (allow). There is
**no silent fallback** from a configured Redis store to in-memory.

### What Redis does not do

It never stores tenant plans, feature entitlements, quota records, audit, business state, AI output,
or permissions. The guard order (entitlement → quota → rate) is unchanged; Redis is consulted only
after entitlement and quota allow, and `enforceWithoutRate(...)` never reaches the store (proven by
test).

## Tests added/updated

Added:
- `SignedActorVerifierStage16KTest` (8) — HMAC accept + full rejection matrix + inert mode.
- `RuntimeEntitlementAdminControllerSignedActorStage16KTest` (6) — signed-mode controller: valid
  accepted + verified actor, missing/invalid/stale → 401, manage permission still required, read
  unaffected.
- `RedisRateLimitStoreStage16KTest` (7) — key/accumulation/isolation/TTL + fail-open/closed (fake
  counter, no Redis).
- `RuntimeGuardRateStoreInteractionStage16KTest` (4) — store-level proof: allowed consumes rate;
  entitlement/quota denial do not; `enforceWithoutRate` does not.
- `RuntimeEntitlementAdminServiceStage16KTest` (6) — bounded-window overlap matrix.
- `RuntimePlanInvariantPostgresIntegrationTest` (Postgres-only) — V43 at the real DB level.

Updated: none of the 16I/16J tests needed changes — the unsigned fallback preserves their behavior
(the controller resolves the actor via the tenant from `TenantContext` leniently, so the no-tenant
mock tests are unaffected).

## Security notes

- No arbitrary actor spoofing in signed mode (HMAC + constant-time compare); body actor ignored.
- No secret or expected signature is logged or returned in errors.
- No cross-tenant plan/feature mutation; foreign-tenant plan → 404; no unscoped repo access.
- Every runtime entitlement admin mutation is audited with the verified/resolved actor.
- No raw SQL string concatenation; Redis keys contain only normalized non-sensitive identifiers.
- Redis failures are handled explicitly (fail-closed by default); no raw Redis details leak to the API.
- No AI calls, no payment/billing secrets.

## Performance notes

- Actor verification is O(1) (one HMAC + constant-time compare).
- Redis consume is O(1) (single atomic Lua INCRBY + first-write EXPIRE).
- Overlap check is one tenant-scoped, index-backed query (`idx_tenant_runtime_plan_tenant_status`); no
  full scans, no heavy joins.
- V43 remains a bounded partial unique index. No object storage / AI / connector calls in guard paths.

## Limitations

- DB-level bounded-window overlap is deferred (no `btree_gist` exclusion constraint); enforced at the
  service level only.
- The Postgres invariant test and the Redis integration require external infrastructure and are not
  part of the normal H2 suite; the Redis store's Lua/Lettuce path (`LettuceRedisRateCounter`) is
  exercised only against a real Redis (the store logic is unit-tested via a fake counter).
- Signed actor is a narrow HMAC control-plane hardening, not a verified OIDC principal; trust in the
  gateway-injected header set remains.
- Redis is single-standalone-configured here (no cluster/sentinel wiring).

## Future candidates (no new stage numbers)

- A `btree_gist` exclusion constraint for bounded-window overlap once the extension is standardized.
- A CI job that runs the Postgres integration tests (V43 + the rest) automatically.
- A Redis Testcontainers contract test for `LettuceRedisRateCounter`.
- A real authenticated principal/identity subsystem to replace gateway-trusted + signed headers.
- Redis cluster/sentinel support and a rate-store health/metrics surface.

## Worktree note

`.gitignore` and `README.md` are user-owned pre-existing dirty files. They were not touched by this
stage; if they appear modified in the worktree they are user-owned pre-existing changes.

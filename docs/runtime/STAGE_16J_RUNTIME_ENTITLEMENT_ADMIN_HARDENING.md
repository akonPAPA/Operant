# OP-CAP-16J — Runtime Entitlement Admin Hardening

## What 16J hardens

16J is a narrow security / data-integrity pass over the 16I runtime entitlement admin command
surface. It adds no new capability. Three hardening changes:

1. **DB-level invariant** for at-most-one open-ended ACTIVE runtime plan per tenant (Postgres partial
   unique index), backing the existing service-level conflict check.
2. **Explicit `effectiveUntil` patch semantics** so an update can reset the effective-until edge to
   open-ended, while an omitted value still means "leave unchanged".
3. **Trusted actor resolution** — the audit actor for admin mutations is resolved from the trusted
   request context, never from a caller-supplied request-body field.

All 16A–16I behavior, the runtime guard ordering (entitlement → quota → rate), the no-plan
compatibility default, and every 16F/16G/16H guarded path are preserved.

## DB invariant decision

**Implemented** as a Postgres partial unique index in `V43__runtime_plan_active_invariant.sql`:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_runtime_plan_active_open
  ON tenant_runtime_plan(tenant_id)
  WHERE status = 'ACTIVE' AND effective_until IS NULL;
```

- Same style as the existing partial indexes in `V39`/`V42`.
- **Scope (deliberately narrow):** blocks a second *open-ended* (`effective_until IS NULL`) ACTIVE
  plan per tenant. It does **not** block historical ACTIVE plans with a closed `effective_until`, nor
  SUSPENDED/EXPIRED/DISABLED plans. Overlapping bounded-but-currently-active windows remain a
  service-level concern (no `btree_gist` range-exclusion constraint is introduced this stage).
- **H2/test compatibility:** the test profile runs on H2 with **Flyway disabled** and Hibernate
  `ddl-auto: create-drop` (schema generated from entities), so this index is **not applied under
  H2** — exactly like the existing `uq_feature_entitlement_open` partial index from V42. Under H2 the
  **service-level conflict check** is the enforcement path, and it is covered by tests. The migration
  file itself is asserted by `RuntimePlanInvariantMigrationStage16JTest` (matching the repo's
  existing migration-file test convention).
- **Data safety:** `tenant_runtime_plan` was introduced empty in V42 and no migration seeds it, so
  the index cannot conflict with existing data. The index is additive and non-destructive.

The service-level invariant was also extended: `updatePlan` now rejects (409 `ConflictException`)
making a plan open-ended ACTIVE when another open-ended ACTIVE plan already exists for the tenant —
the same invariant the DB index enforces on Postgres.

## effectiveUntil patch semantics

`UpdateTenantRuntimePlanRequest` / `UpdatePlanCommand` gained a `clearEffectiveUntil` boolean. The
entity mutator now takes an explicit `setEffectiveUntil` flag instead of treating `null` as
"ignore". Behavior:

| Input | Result |
|-------|--------|
| `clearEffectiveUntil = true` | `effective_until` reset to `null` (open-ended) |
| `effectiveUntil` provided (non-null) | `effective_until` set to that value |
| neither provided | `effective_until` left unchanged |
| `clearEffectiveUntil = true` **and** `effectiveUntil` provided | rejected (400, contradictory) |

- Invalid window (`effectiveUntil` not after `effectiveFrom`, using resulting values) is still
  rejected (400).
- Clearing respects the open-ended ACTIVE conflict invariant (409 if it would create a second
  open-ended ACTIVE plan).
- The audit event records both `previousEffectiveUntil` and the new `effectiveUntil`.

## Actor resolution model

This service has **no Spring Security principal**; trust is carried by gateway-set headers — the same
trust boundary already used for `X-Tenant-Id` (tenant) and `X-OrderPilot-Permissions` (authorization).
16J adds a small `RequestActorResolver` (`com.orderpilot.security`) used by the admin controller:

- **Trusted source:** the `X-OrderPilot-Actor-Id` request header. When present and a valid UUID, it
  is the audit actor.
- **Controlled fallback:** when the header is absent/blank, the resolver returns a stable
  `SYSTEM_ACTOR` sentinel (`00000000-0000-0000-0000-000000000000`), recorded as the audit actor —
  never a body value. This is the auditable default for internal/dev/test calls that omit the header.
- **Malformed trusted input:** a present-but-invalid actor header is rejected (400) rather than
  silently mis-attributed.
- **No body spoofing:** `actorId` was **removed** from all runtime-entitlement mutation request DTOs
  (`CreateTenantRuntimePlanRequest`, `UpdateTenantRuntimePlanRequest`, `UpsertFeatureEntitlementRequest`,
  `DisableFeatureEntitlementRequest`). The controller resolves the actor from the trusted context and
  passes it into the service command; a body `actorId` (even if sent) is ignored (proven by test).

The service command records still carry `actorId` internally (the controller supplies the resolved
value), so audit attribution is unchanged in shape — only its **source** is now trusted.

### Trusted vs fallback actor behavior

| Request | Audited actor |
|---------|---------------|
| `X-OrderPilot-Actor-Id: <uuid>` | that uuid |
| no actor header | `SYSTEM_ACTOR` sentinel |
| invalid actor header | request rejected (400) |
| body `actorId` only | ignored — resolved actor (header or system) is used |

## Audit behavior

Audit events are unchanged in type (`RUNTIME_PLAN_CREATED`, `RUNTIME_PLAN_UPDATED`,
`FEATURE_ENTITLEMENT_UPSERTED`, `FEATURE_ENTITLEMENT_DISABLED`) and still go through the existing
`AuditEventService`. Changes: the actor is the resolved trusted actor (not a body value), and the
`RUNTIME_PLAN_UPDATED` metadata now includes `previousEffectiveUntil` / `effectiveUntil`. Metadata
remains safe stable tokens with JSON-escaped strings.

## Tenant isolation rules

Unchanged from 16I and re-verified: tenant comes from `TenantContext.requireTenantId()`; plan lookups
use `findByIdAndTenantId`; a foreign-tenant plan id is invisible (404, never leaked); no unscoped
`findById`; no cross-tenant mutation. The new `updatePlan` conflict check queries only the current
tenant's plans.

## Security notes

- No arbitrary actor spoofing: the audit actor for HTTP admin mutations is resolved from a trusted
  header, not the request body (proven by `bodyActorIdCannotSpoofResolvedActor`).
- No cross-tenant plan/feature mutation; foreign-tenant plan ids → 404.
- No unscoped repository access; all queries tenant-scoped and bounded.
- Every mutation is audited with the resolved actor and safe, JSON-escaped metadata.
- No raw SQL string concatenation (migration is static DDL; queries are Spring Data derived).
- No plan internals leaked to unauthorized users; reads/mutations still gated by
  `RUNTIME_ENTITLEMENT_READ` / `RUNTIME_ENTITLEMENT_MANAGE`.
- No external calls, no AI calls, no billing/payment secrets.

## Performance notes

- The DB invariant is a partial unique index (Postgres) — O(log n) on insert/update, no scans.
- `updatePlan`'s conflict check reuses the existing tenant-scoped, index-backed plan query
  (`idx_tenant_runtime_plan_tenant_status`); no full scans, no heavy joins, no cache/Redis.
- Actor resolution is a single header read.

## Limitations

- The DB invariant covers only **open-ended** ACTIVE plans; two **bounded** but currently-overlapping
  ACTIVE windows are prevented only at the service level (no range-exclusion constraint). Adding a
  `btree_gist` exclusion constraint is a candidate for a later stage.
- Under H2 (tests), the DB index is not applied (Flyway disabled); the service-level conflict check is
  the enforcement path there. Postgres environments get both.
- Actor identity is a trusted gateway header (`X-OrderPilot-Actor-Id`), not a verified authenticated
  principal — there is still no real auth/identity subsystem in this service. This is a deliberate
  scope boundary (no broad auth rewrite).
- No frontend UI, Redis, billing, or distributed rate store (out of scope).

## Recommended OP-CAP-16K scope

- Introduce a real authenticated-principal/identity context (or signed actor assertion) so the actor
  is verified, not merely trusted-by-gateway-header.
- Add a `btree_gist` exclusion constraint (or equivalent) to prevent overlapping **bounded** active
  plan windows at the DB level.
- Add a focused Postgres-profile integration test that actually exercises the V43 unique index
  (the H2 test profile cannot).
- Consider a distributed `RateLimitStore` (e.g. Redis) behind the existing interface — still no
  billing.

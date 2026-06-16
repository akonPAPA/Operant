# Stage 16E — Persistent Tenant Plans and Feature Entitlements

## What 16E adds

16E replaces the 16D code-defined **permissive** runtime feature policy with a
**database-backed, tenant-scoped** policy. Feature availability is now resolved from
persistent `tenant_runtime_plan` and `feature_entitlement` records, so a tenant can be
denied an expensive runtime feature (e.g. AI document extraction) through real config,
while every existing/demo tenant keeps working via a safe compatibility default.

This is **runtime governance, not billing**. There is no payment, subscription charging,
Stripe, Redis, admin UI, or pricing logic in this stage. `limit`/plan codes carry no money.

The runtime guard's public API and ordering are unchanged — only the entitlement source
became persistent.

## DB tables (migration `V42__persistent_tenant_entitlements.sql`)

`tenant_runtime_plan`
- `id` UUID PK, `tenant_id` UUID NOT NULL → `tenant(id)`
- `plan_code` VARCHAR — `FREE` / `PILOT` / `PRO` / `ENTERPRISE` / `CUSTOM` (CHECK)
- `status` VARCHAR — `ACTIVE` / `SUSPENDED` / `EXPIRED` / `DISABLED` (CHECK)
- `effective_from` TIMESTAMPTZ NOT NULL, `effective_until` TIMESTAMPTZ NULL
- `created_at`, `updated_at` TIMESTAMPTZ
- Index: `(tenant_id, status, effective_from DESC)`

`feature_entitlement`
- `id` UUID PK, `tenant_id` UUID NOT NULL → `tenant(id)`, `plan_id` UUID NOT NULL → `tenant_runtime_plan(id)`
- `feature_type` VARCHAR (the `RuntimeFeatureType.name()` token), `enabled` BOOLEAN NOT NULL
- `reason_code` VARCHAR NULL, `effective_from` NOT NULL, `effective_until` NULL
- `created_at`, `updated_at` TIMESTAMPTZ
- Indexes: `(tenant_id, feature_type)`, `(tenant_id, plan_id, feature_type)`
- Partial unique index `(tenant_id, plan_id, feature_type) WHERE effective_until IS NULL`
  (Postgres only — prevents duplicate open-ended rows; not applied under the H2 test
  schema, where the repository query resolves duplicates deterministically)

Tests run on H2 with Hibernate `create-drop` (Flyway disabled), so the test schema comes
from the JPA entity mappings; the migration provides Postgres parity.

## Runtime guard order (unchanged)

```
feature entitlement → quota (read-only) → rate limit (consumes budget) → expensive work
```

`RuntimeGuardService.enforce(request, featureType)` still runs entitlement first. An
entitlement denial short-circuits **before** the quota read and **before** any rate budget
is consumed; a quota denial short-circuits before rate. The no-feature 16C/16D APIs
(`enforce(request)` / `checkRuntimeGuard(request)`) are preserved and skip the entitlement
gate entirely.

The guard live-wiring point is still `ExtractionPipelineService.runNow(...)` — the first
effective line, before run/job creation or any extraction provider work. Requested units
remain **1/run** (real unit estimation is deferred to 16F).

## Default behavior

| Tenant state | Result | Reason code |
| --- | --- | --- |
| No plan row at all | **allow** | `FEATURE_POLICY_COMPAT_DEFAULT` |
| Plan row(s) but none currently active (suspended/expired/disabled or outside window) | deny | `FEATURE_PLAN_NOT_ACTIVE` |
| Active plan, effective enabled entitlement | allow | `FEATURE_AVAILABLE` |
| Active plan, effective disabled entitlement | deny | `FEATURE_NOT_AVAILABLE` |
| Active plan, entitlement window ended | deny | `FEATURE_ENTITLEMENT_EXPIRED` |
| Active plan, no/future entitlement (authoritative) | deny | `FEATURE_NOT_ENTITLED` |

**Why allow-by-default for no plan:** it preserves 16D behavior and matches the existing
product convention (16B "allow when no quota policy", 16C "allow within budget"). No
existing tenant has plan rows, so the persistent policy is opt-in: governance applies only
once a tenant is explicitly given a plan. **An active plan is authoritative** — once a
tenant has an active plan, a feature must be explicitly entitled.

Duplicate rows are resolved deterministically: the active plan is the newest-effective
`ACTIVE` plan; the entitlement is the currently-effective row with the latest
`effective_from`.

## Entitlement denial behavior

`RuntimeGuardService.enforce(...)` maps any feature-denial reason code
(`RuntimeGuardReasonCodes.isFeatureDenial(...)`) to `RuntimeFeatureNotAvailableException`,
which the existing `GlobalExceptionHandler` renders as **HTTP 403** with stable error code
`RUNTIME_FEATURE_NOT_AVAILABLE` in the standard `ApiErrorResponse` shape (16D mapping
unchanged). Quota → 403 `RUNTIME_QUOTA_EXCEEDED`; rate → 429 `RUNTIME_RATE_LIMITED` +
`Retry-After`. A denial creates no `ExtractionRun` and calls no provider.

## Security notes

- All entitlement reads are tenant-scoped (`findByTenantId...`); no cross-tenant access.
- No external calls, no AI calls, no business-table writes in the entitlement path.
- User-facing denials expose only a stable reason code — no plan code, window, or other
  plan internals leak.
- Denial happens before quota/rate consumption and before any expensive work.
- Lookups are indexed and bounded (no full-table scans, no heavy joins in the hot path).

## Limitations

- Requested units are still fixed at 1/run (no real estimate yet → 16F).
- The entitlement check issues two small indexed queries per guarded call (acceptable on
  the expensive extraction path; not applied to cheap GET endpoints).
- No admin API/UI to manage plans/entitlements (records are seeded directly); intentional
  for this stage.
- Redis rate store still deferred; in-memory store remains.
- Only `AI_DOCUMENT_EXTRACTION` is enforced at a live boundary.

## Recommended next stage

**OP-CAP-16F — requested-unit estimation + wider guard wiring:** introduce a
`RuntimeUnitEstimator` (real page/line/payload-based units) and wire `enforce(...)` into
additional high-cost paths — bulk import, reconciliation run, AI explanation generation,
report generation, channel/bot workload routing — reusing `RuntimeFeatureType` /
`RuntimeOperationType`. Optionally add an admin command surface to manage plans and a
distributed `RateLimitStore`.

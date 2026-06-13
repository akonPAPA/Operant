# OP-CAP-16I — Tenant Plan & Feature Entitlement Command Surface

## What 16I adds

16E made tenant runtime plans and feature entitlements **persistent**, but they could only be created
or changed directly in the database/tests. 16I adds the missing **controlled backend command/query
surface** so plans and entitlements are managed safely, with permissions and audit, instead of by
raw DB edits.

- A single service, `RuntimeEntitlementAdminService`, owns all create/update/upsert/disable/read
  logic.
- A thin internal/admin controller, `RuntimeEntitlementAdminController`, exposes it under
  `/api/v1/runtime`.
- Two dedicated permissions gate the surface (`RUNTIME_ENTITLEMENT_READ` /
  `RUNTIME_ENTITLEMENT_MANAGE`).
- Every mutation emits an audit event.

The records this surface writes are exactly the rows the existing 16E `PersistentRuntimeFeaturePolicy`
already resolves, so the runtime guard immediately respects command-driven changes with **no guard
code change**.

## Why this is not billing

This is a runtime **governance** control surface, not a billing/subscription product. There is no
price, money, invoice, payment, Stripe, or subscription concept anywhere in 16I. `TenantRuntimePlanCode`
remains a governance/packaging label only. No payment secrets, no external calls, no AI calls are
introduced.

## Command / API surface

All routes are under `/api/v1/runtime` (so the existing `/api/v1/**` permission interceptor applies).
Tenant scope comes from `TenantContext` (`X-Tenant-Id`); **no request body carries a tenantId**, so
the surface cannot target another tenant.

| Method | Path | Permission | Purpose |
|--------|------|------------|---------|
| GET    | `/api/v1/runtime/entitlements` | `RUNTIME_ENTITLEMENT_READ` | Current plan + per-feature status as the guard sees it |
| POST   | `/api/v1/runtime/plans` | `RUNTIME_ENTITLEMENT_MANAGE` | Create a runtime plan |
| PATCH  | `/api/v1/runtime/plans/{planId}` | `RUNTIME_ENTITLEMENT_MANAGE` | Update plan status / effective window |
| POST   | `/api/v1/runtime/plans/{planId}/features` | `RUNTIME_ENTITLEMENT_MANAGE` | Upsert a feature entitlement (feature in body) |
| PATCH  | `/api/v1/runtime/plans/{planId}/features/{featureType}` | `RUNTIME_ENTITLEMENT_MANAGE` | Upsert a feature entitlement (feature in path) |
| DELETE | `/api/v1/runtime/plans/{planId}/features/{featureType}` | `RUNTIME_ENTITLEMENT_MANAGE` | Disable a feature entitlement |

Service methods: `createPlan`, `updatePlan`, `upsertFeatureEntitlement`, `disableFeatureEntitlement`,
`getCurrentRuntimeEntitlements`. The controller holds no business logic — it maps request DTOs to
service command records.

### Response DTOs

`TenantRuntimePlanResponse`, `FeatureEntitlementResponse`, and `RuntimeEntitlementStatusResponse`
(tenantId, `source`, `currentPlan`, `featureStatuses`). They expose only stable governance fields —
no pricing, no billing, no internal DB columns. `RuntimeEntitlementStatusResponse.source` is one of
`COMPATIBILITY_DEFAULT` (no plan rows), `ACTIVE_PLAN`, or `PLAN_NOT_ACTIVE`, mirroring how the 16E
policy classifies the tenant.

## Permissions added

- `RUNTIME_ENTITLEMENT_READ` — read plan/feature status. Platform/admin level; not for general
  operators.
- `RUNTIME_ENTITLEMENT_MANAGE` — create/update plans and entitlements. Platform/admin level.

Wired in `ApiPermissionInterceptor`: any non-GET under `/api/v1/runtime` requires
`RUNTIME_ENTITLEMENT_MANAGE`; GETs require `RUNTIME_ENTITLEMENT_READ`. Read permission alone does not
grant mutation (proven by test).

## Audit events

Every mutation calls the existing `AuditEventService.record(...)` (no parallel audit system):

| Action | Entity type | Entity id | Safe metadata |
|--------|-------------|-----------|----------------|
| `RUNTIME_PLAN_CREATED` | `TENANT_RUNTIME_PLAN` | plan id | planCode, status |
| `RUNTIME_PLAN_UPDATED` | `TENANT_RUNTIME_PLAN` | plan id | previousStatus, status |
| `FEATURE_ENTITLEMENT_UPSERTED` | `FEATURE_ENTITLEMENT` | row id | feature, enabled, reasonCode |
| `FEATURE_ENTITLEMENT_DISABLED` | `FEATURE_ENTITLEMENT` | row id | feature, enabled=false, reasonCode |

Audit events carry actor (optional `actorId`), tenant (from `TenantContext`), operation, plan/feature
id, before/after value summary, and reason code. The operator-provided `reasonCode` is JSON-escaped
before being written into metadata (no JSON injection, no raw control characters).

## Tenant isolation rules

- All reads/writes resolve tenant from `TenantContext.requireTenantId()`.
- Plan lookups use `TenantRuntimePlanRepository.findByIdAndTenantId(...)`: a plan owned by another
  tenant is invisible and surfaces as **404**, never leaking its existence (proven by test — tenant A
  cannot update/upsert against tenant B's plan).
- Entitlement reads use tenant- and plan-scoped queries only. No unscoped `findById` is used in the
  admin service.

## Conflict behavior for active plans

Creating a plan while another plan is **currently active** is rejected with **409 CONFLICT** (new
`ConflictException` → `GlobalExceptionHandler`). 16I deliberately prefers an explicit conflict over
silently expiring/switching the existing plan, so plan transitions are always an intentional
`updatePlan` call. (To switch plans, an admin first suspends/expires the current plan via
`PATCH`, then creates the new one.)

## Validation / error handling

- Unknown `featureType` (path) → **400** (`IllegalArgumentException`).
- Unknown `planCode` / plan `status` (enum body) → **400** (unreadable-message handler).
- `effectiveUntil` not after `effectiveFrom` → **400**.
- Plan not found / another tenant's plan → **404**.
- Conflicting active plan → **409**.
- Missing permission → existing **403** (`TenantPolicyException`).

## How this connects to PersistentRuntimeFeaturePolicy

The admin service writes `tenant_runtime_plan` / `feature_entitlement` rows; the 16E
`PersistentRuntimeFeaturePolicy` (the default `RuntimeFeaturePolicy` bean) reads exactly those rows on
each guard check. There is no cache between them, so a command takes effect on the very next guard
evaluation. `RuntimeGuardIntegrationStage16ITest` proves the full lifecycle end-to-end over the
`REPORT_EXPORT` seam:

1. create active plan + **disabled** `REPORT_EXPORT` → `enforceWithoutRate(...)` denies
   (`RuntimeFeatureNotAvailableException`);
2. **enable** `REPORT_EXPORT` → guard allows;
3. **suspend** plan → guard denies (`FEATURE_PLAN_NOT_ACTIVE`).

Upsert reuses the existing open-ended `(tenant, plan, feature)` row rather than creating duplicate
open-ended rows, so the policy's deterministic "latest effective row" resolution stays unambiguous.

## Security notes

- Management endpoints require an explicit dedicated permission; read permission alone cannot mutate.
- No cross-tenant plan/feature mutation: tenant comes from `TenantContext`, plan lookups are
  tenant-scoped, foreign-tenant plans return 404.
- No unscoped repository access in the admin service.
- Response DTOs expose only governance fields — no plan internals, pricing, or DB columns leaked; a
  denial still surfaces only stable reason-code tokens.
- No raw SQL string concatenation — only Spring Data derived queries.
- Every mutation is audited with JSON-escaped, safe metadata.
- No external calls, no AI calls, no payment secrets.
- Runtime guard ordering (entitlement → quota → rate) and the no-plan compatibility default are
  unchanged.

## Performance notes

- All admin commands use bounded, tenant-scoped queries (`findByIdAndTenantId`,
  `findByTenantIdAndPlanId`, `findByTenantIdAndPlanIdAndFeatureType`, `findByTenantIdOrderBy...`).
- No full-table scans, no heavy joins, no caching layer, no Redis.
- The read endpoint evaluates each `RuntimeFeatureType` against the policy (a small fixed enum set),
  each a bounded tenant-scoped lookup.

## Limitations

- One active plan per tenant is enforced by command-time conflict rejection, not yet by a DB
  constraint; concurrent creators could theoretically both pass the check (mitigated by the small
  admin audience and transactional writes). A partial unique index is a candidate hardening.
- `updatePlan` cannot clear an `effectiveUntil` back to open-ended (null is treated as "leave
  unchanged"); set a far-future value instead, or this can be revisited if a clear-window need arises.
- No frontend/admin UI (explicitly out of scope) — this is a backend command surface only.
- Rate-limit store remains in-memory (no Redis); unchanged from 16C–16H.
- Actor identity is supplied by the caller (`actorId`); there is no separate authenticated-principal
  resolution in this stage (consistent with the existing Stage 9 command convention).

## Recommended OP-CAP-16J scope

- Add a DB-level guarantee for "at most one active plan per tenant" (partial unique index /
  migration) to make the conflict rule structural.
- Introduce a distributed `RateLimitStore` (e.g. Redis) behind the existing interface — no service
  changes required.
- Optional minimal read-only admin frontend for plan/entitlement visibility (still no billing).
- Authenticated-principal actor resolution (replace caller-supplied `actorId`) once an auth-principal
  context exists.

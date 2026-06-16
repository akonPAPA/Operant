# Stage 16D — Runtime Guard Live Wiring + Feature Entitlement Foundation

## Goal

Make the OP-CAP-16C runtime guard real by wiring it into one narrow, high-cost path
*before* expensive work runs, and add a minimal, code-defined feature-entitlement gate
so `RUNTIME_FEATURE_NOT_AVAILABLE` can deny unavailable features per tenant.

This stage does **not** implement billing.
This stage does **not** add Redis.
This stage does **not** add frontend.
This stage does **not** introduce global runtime interception.
This stage wires runtime protection into one narrow expensive path only.

## Selected expensive path

`ExtractionPipelineService.runNow(ExtractionRunRequest)` — the service method that runs
heavy text + semantic AI document/message extraction.

**Why this path:**

- It is the single seam through which both expensive extraction entry points flow
  (`POST /api/v1/extractions/runs/execute` and `POST /api/v1/processing/jobs/{id}/run-extraction`),
  so guarding one service method protects both endpoints without controller or global wiring.
- It triggers real model/OCR/parsing work (`TextExtractionService` → `SemanticExtractionService`)
  and creates an `ExtractionRun` job record — exactly the kind of cost the guard must precede.
- The guard is placed as the **first statement** in `runNow`, before `runService.create(...)`,
  so a denial creates no `ExtractionRun`, calls no extraction provider, and mutates no business state.
- It does not touch Stage 14/15 validation-review flows.

Cheap GET/read endpoints are intentionally **not** guarded.

## Guard order

The runtime chain at the boundary is:

```
authentication → tenant resolution → permission   (existing app conventions)
  → feature entitlement → quota (read-only) → rate limit (consumes budget)
  → expensive job/work creation
```

`RuntimeGuardService.enforce(request, featureType)` runs **entitlement → quota → rate**:

1. **Feature entitlement** (cheapest, no DB write) runs first. If the tenant lacks the
   feature, the call throws immediately — neither the quota read nor the rate budget is touched.
2. **Quota** (read-only, reuses 16B `UsageMeterService.checkQuota`). If it denies, the
   rate-limit window budget is **not** consumed.
3. **Rate limit** (weighted fixed window, consumes budget) runs only after feature and quota pass.

The original 16C API (`enforce(request)` / `checkRuntimeGuard(request)`, no feature
argument) is preserved and simply skips the entitlement gate.

## Requested units

The extraction path charges **1 unit per run**. No page/line/payload metric is available
*before* extraction, so this is a deliberate foundation value
(`ExtractionPipelineService.EXTRACTION_REQUESTED_UNITS`). A later stage can pass a real
estimate (page/line count) once known. Units are clamped non-negative and accumulation
saturates (16B/16C `UsageMath`), so overflow/negative values are impossible.

## Entitlement / quota / rate denial behavior

| Gate | Denied decision reason | Exception (from `enforce`) | HTTP | Error code |
| --- | --- | --- | --- | --- |
| Feature | `FEATURE_NOT_AVAILABLE` | `RuntimeFeatureNotAvailableException` | 403 | `RUNTIME_FEATURE_NOT_AVAILABLE` |
| Quota | `QUOTA_LIMIT_EXCEEDED` | `RuntimeQuotaExceededException` | 403 | `RUNTIME_QUOTA_EXCEEDED` |
| Rate | `RATE_LIMIT_EXCEEDED` | `RuntimeRateLimitedException` | 429 + `Retry-After` | `RUNTIME_RATE_LIMITED` |

All three exceptions extend `RuntimeLimitException` and are mapped by the existing
`GlobalExceptionHandler` to the standard `ApiErrorResponse` shape (the 429 carries a
`Retry-After` header). Denial messages expose no tenant plan internals.

## Feature entitlement foundation (code-defined)

16B did not ship a `TenantPlan` / `FeatureEntitlement` table — only `QuotaPolicy`. So
16D adds a **code-defined** entitlement gate (no migration, no billing), consistent with
the 16C code-defined rate policy:

- `RuntimeFeatureType` — `AI_DOCUMENT_EXTRACTION`, `AI_VALIDATION_HANDOFF`,
  `BULK_DOCUMENT_PROCESSING`, `RECONCILIATION_RUN` (only `AI_DOCUMENT_EXTRACTION` is
  enforced at a live boundary this stage).
- `RuntimeFeaturePolicy` — tenant-scoped `isAvailable(tenantId, feature)`.
- `PermissiveRuntimeFeaturePolicy` — the **default**: every feature available to every
  tenant. This preserves the existing permissive product convention (16B "allow when no
  quota policy", 16C "allow within budget"). Registered via
  `CoreConfiguration` `@Bean @ConditionalOnMissingBean`, so a future tenant-entitlement
  backed policy replaces it without changing the guard.
- `FeatureEntitlementGuard` / `FeatureEntitlementDecision` — read-only check producing a
  stable decision.
- `RuntimeFeatureNotAvailableException` — 403 / `RUNTIME_FEATURE_NOT_AVAILABLE`.

**Chosen default: permissive (allow).** Tests document this (a tenant is denied only when
a policy explicitly marks the feature unavailable for that tenant).

## Error codes

- `RUNTIME_FEATURE_NOT_AVAILABLE` → 403 (newly enforced this stage).
- `RUNTIME_QUOTA_EXCEEDED` → 403 (16C).
- `RUNTIME_RATE_LIMITED` → 429 + `Retry-After` (16C).

## What is intentionally not implemented

- No billing provider, Stripe/Adyen/payment, or subscription logic.
- No Redis / Kafka.
- No persistent `TenantPlan` / `FeatureEntitlement` tables or migration (code-defined policy only).
- No frontend, dashboard usage screen, pricing page, or subscription admin UI.
- No global controller interceptor / advice for guarding; one service-level boundary only.
- No durable job-runtime changes; no refactor of validation review or 16A/16B/16C internals.
- No new estimate metric for requested units (charges 1/run for now).

## Test coverage

- `RuntimeGuardServiceStage16DTest` (9): feature available→quota→rate allowed; feature
  unavailable decision; `enforce` throws `RuntimeFeatureNotAvailableException`; feature
  denial consumes no rate budget; feature short-circuits quota; quota denial consumes no
  rate budget; rate denial throws 429; 16C no-feature API still works; feature denial is
  tenant-scoped.
- `ExtractionPipelineGuardStage16DTest` (5): allowed creates an `ExtractionRun`; feature
  denial → `RuntimeFeatureNotAvailableException` and **no** run; quota denial → no run;
  rate denial after budget → no extra run; tenant isolation (A denied, B succeeds).
- `GlobalExceptionHandlerTest` (+2): feature denial → 403 `RUNTIME_FEATURE_NOT_AVAILABLE`;
  rate denial → 429 with `Retry-After`.
- Regression: 16A (17), 16B (15), 16C (15), extraction pipeline (3), and full-context
  Stage 14/15 validation suites all remain green.

## How the next stage (16E) should extend this

- Replace `PermissiveRuntimeFeaturePolicy` with a persistent, tenant-entitlement-backed
  `RuntimeFeaturePolicy` (introduce `TenantPlan` / `FeatureEntitlement` tables + migration)
  behind the same interface.
- Pass a real requested-units estimate (page/line count) from the extraction path.
- Wire the guard into additional high-cost boundaries (bulk import, reconciliation,
  validation AI handoff) one at a time, reusing `RuntimeFeatureType` / `RuntimeOperationType`.
- Optionally add a distributed `RateLimitStore` (e.g. Redis) for multi-node deployments —
  still behind the existing interface, no service change.

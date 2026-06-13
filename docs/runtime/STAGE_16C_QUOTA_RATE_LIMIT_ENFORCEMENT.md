# Stage 16C — Quota + Rate Limit Enforcement

## Goal

Add the first runtime protection layer that expensive paths (AI routing, document
extraction, bulk import, reconciliation) can call **before** creating a job or doing
any AI/model/heavy work. It turns the OP-CAP-16B advisory usage/quota foundation into
deterministic, tenant-scoped enforcement decisions, and adds a tenant-scoped weighted
rate limiter.

It builds directly on:

- **16A** — `AiRoutingDecision` / workload classification.
- **16B** — `UsageEvent`, `UsageCounter`, `QuotaPolicy`, `UsageMeterService.checkQuota`.

## Non-goals

- No billing. No Stripe/Adyen/payment/subscription provider. No monetary amounts.
- No frontend.
- No broad `TenantPlan` / `FeatureEntitlement` model (deferred — see below).
- No global/automatic interception of all APIs. No controller wiring in this stage.
- No Redis dependency (none is wired in the backend or test profile).
- No AI authority, no AI-worker writes to business tables, no external connector calls.
- No new microservice.

## Architecture

All classes live in `com.orderpilot.application.services.runtime` (with 16A/16B).

| Concern | Type | Notes |
| --- | --- | --- |
| Operation taxonomy | `RuntimeOperationType` | AI routing/extraction, upload, channel msg, bulk, reconciliation, search, report |
| Guard request | `RuntimeGuardRequest` | typed safe fields only; no free-text metadata |
| Guard decision | `RuntimeGuardDecision` | allowed, httpStatusHint, reasonCode, quota fields, retryAfter, bucket |
| Quota guard | `QuotaGuard` | read-only adapter over `UsageMeterService.checkQuota` |
| Rate decision | `RateLimitDecision` | allowed, reason, bucket, weight, budget, used, retryAfter |
| Rate limiter | `RateLimitService` | tenant+operation, weighted fixed window, `Clock`-driven |
| Rate store | `RateLimitStore` / `InMemoryRateLimitStore` | interface + in-process default |
| Weights/rules | `EndpointWeightPolicy` / `RateLimitRule` | code-defined cost weights, window budgets, default metrics |
| Retry-After | `RetryAfterPolicy` | deterministic seconds-until-window-end (≥ 1) |
| Orchestration | `RuntimeGuardService` | quota-then-rate; `checkRuntimeGuard` / `enforce` |
| Errors | `RuntimeLimitException` + `RuntimeQuotaExceededException` (403) / `RuntimeRateLimitedException` (429) | mapped in `GlobalExceptionHandler` |

`RuntimeGuardService.enforce(...)` is the entry point an expensive path calls. Order is
deliberate: the cheap, **read-only** quota check runs first; if quota denies, the call
returns/throws immediately and the rate-limit window budget is **not** consumed — a
denied request never burns rate budget or triggers downstream work. Only when quota
allows does the weighted rate check run.

The `InMemoryRateLimitStore` is registered as the default `RateLimitStore` bean via a
`@Bean @ConditionalOnMissingBean` method in `CoreConfiguration` (matching the existing
`SecretVaultService` pattern), so a distributed implementation can replace it without
touching `RateLimitService`.

## Reason codes

Decision reason codes (`RuntimeGuardReasonCodes`):

- `ALLOWED` — passed quota and rate.
- `NO_POLICY` — no quota policy (or no quota dimension for the operation); allowed.
- `WITHIN_LIMIT` — quota policy exists and usage fits.
- `QUOTA_LIMIT_EXCEEDED` — quota policy exists and usage + requested exceeds the limit.
- `RATE_LIMIT_WITHIN_WINDOW` — weighted window budget has room.
- `RATE_LIMIT_EXCEEDED` — weighted window budget exhausted.

API error codes (`RuntimeErrorCodes`):

- `RUNTIME_QUOTA_EXCEEDED` → HTTP **403**.
- `RUNTIME_RATE_LIMITED` → HTTP **429** with `Retry-After`.
- `RUNTIME_FEATURE_NOT_AVAILABLE` → HTTP 403 — **reserved**, not thrown yet (no
  entitlement model in 16C).

## Quota semantics

`QuotaGuard.checkQuota` is read-only and reuses 16B `UsageMeterService.checkQuota`
(the `QuotaPolicy` table is the enforcement source):

- Operation with no quota dimension (`SEARCH_QUERY`, `REPORT_GENERATED`) → allow `NO_POLICY`.
- No policy for the metric → allow `NO_POLICY`.
- `used + requested ≤ limit` → allow `WITHIN_LIMIT`.
- `used + requested > limit` → deny `QUOTA_LIMIT_EXCEEDED` (403).

Requested units follow the 16B convention: negative/absurd values are **clamped to a
non-negative `long`**; accumulation is overflow-safe (saturating at `Long.MAX_VALUE`).
The guard does **not** increment usage — usage metering stays explicit through
`UsageMeterService.recordUsage(...)`.

Default operation → metric mapping (overridable per request):

| Operation | Metric |
| --- | --- |
| `AI_ROUTING_DECISION`, `AI_DOCUMENT_EXTRACTION`, `BULK_IMPORT` | `AI_INPUT_UNITS` |
| `DOCUMENT_UPLOAD` | `DOCUMENT_UPLOAD` |
| `CHANNEL_MESSAGE_RECEIVED` | `CHANNEL_MESSAGE` |
| `RECONCILIATION_RUN` | `RECONCILIATION_RUN` |
| `SEARCH_QUERY`, `REPORT_GENERATED` | none (no quota dimension) |

## Rate limit semantics

Tenant-scoped + operation-scoped, deterministic, weighted **fixed window** (60s):

- `EndpointWeightPolicy.weightFor` — cheap reads = 1; heavier operations cost more
  (`AI_ROUTING_DECISION` 2 … `AI_DOCUMENT_EXTRACTION` 8, `BULK_IMPORT` 10).
- `EndpointWeightPolicy.ruleFor` — per-operation window budget; heavier operations
  carry a smaller budget, so their effective call allowance is stricter.
- Window start = `floor(now / windowSeconds) * windowSeconds` (UTC, via injected `Clock`).
- Each check consumes the operation's weight; `used > budget` → deny `RATE_LIMIT_EXCEEDED`.
- `RetryAfterPolicy` returns whole seconds until the window ends, clamped to ≥ 1.
- When the window advances, the per-key counter resets (fresh budget).

The rate limiter consumes its own window bucket (that is its purpose); it never touches
the 16B usage counters.

## Why billing is excluded

16C is a runtime **protection** layer, not a commercial one. It measures and limits
resource consumption to protect the platform and tenants, using counts of metric units
(`long`), never money. Pricing, invoicing, plans, and payment providers are explicitly
out of scope and would be a separate, clearly-bounded initiative. Keeping billing out
preserves the safety model (no payment data, no external provider) and keeps enforcement
deterministic and testable.

## Why the Redis adapter is deferred

No Redis is wired anywhere in the backend or the test profile. Introducing it now would
add an external dependency to unit/Spring tests and a moving part with no current
multi-node requirement. Instead, 16C ships a single-node, deterministic
`InMemoryRateLimitStore` behind the `RateLimitStore` interface and registers it via
`@ConditionalOnMissingBean`. A `RedisRateLimitStore` can be added in a later stage
(when horizontal scaling needs a shared window) without changing `RateLimitService`.

## Test coverage

`RuntimeGuardServiceStage16CTest` (15 tests):

1. Quota allows when no policy exists.
2. Quota allows within limit.
3. Quota denies when exceeded (403 hint).
4. Existing usage counter is respected.
5. Negative requested units normalized to zero (16B convention).
6. Large values do not overflow.
7. Quota is tenant-scoped (A does not affect B).
8. Rate limit allows within window.
9. Rate limit denies after weighted budget exhausted.
10. Retry-after deterministic and positive on denial.
11. Bucket resets after the window advances.
12. Endpoint weights applied (expensive consumes more than cheap).
13. Stable reason codes returned (quota-first short-circuit, no rate consumed).
14. Quota denial maps to stable 403 `RUNTIME_QUOTA_EXCEEDED`.
15. Rate denial maps to stable 429 `RUNTIME_RATE_LIMITED` with positive Retry-After.

Regression: 16A (17), 16B (15), and the full-context Stage 14/15 validation tests plus
`GlobalExceptionHandlerTest` all remain green (the full `@SpringBootTest` context proves
the new beans wire cleanly).

## Next recommended stage

**OP-CAP-16D** — wire `RuntimeGuardService.enforce(...)` into a narrow, high-cost path
(e.g. AI extraction / bulk import intake) ahead of job creation, and introduce the
`TenantPlan` / `FeatureEntitlement` model behind `RUNTIME_FEATURE_NOT_AVAILABLE`, plus an
optional `RedisRateLimitStore` for multi-node deployments.

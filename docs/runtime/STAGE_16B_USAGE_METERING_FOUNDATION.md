# Stage 16B — Usage Metering Foundation

## Purpose

Establish the first durable, backend-only foundation for measuring tenant usage,
quota-relevant activity, and AI/workload consumption. It gives later stages a place
to record what a tenant consumed and a fast aggregated counter to check limits
against — without yet enforcing anything in a live request path.

It consumes Stage 16A (`AiWorkloadClassifier` → `AiRoutingDecision`) outputs as the
primary usage source: `estimatedInputUnits`, `workloadType`, `selectedTier`,
`asyncRequired`, `humanReviewRequired`, and `reasonCode`.

## Scope

- Append-only `UsageEvent` recording.
- Aggregated `UsageCounter` per `(tenantId, metricType, periodKey)` with overflow-safe `long` totals.
- `QuotaPolicy` per-metric limit foundation.
- `UsageMeterService` with:
  - `recordUsage(UsageRecordingRequest)`
  - `recordAiRoutingDecision(UUID tenantId, AiRoutingDecision, sourceRef, idempotencyKey)`
  - `checkQuota(UUID tenantId, UsageMetricType, long additionalUnits)` — advisory only.
- Idempotent recording (no double-count on retry).
- Stage 16B test suite + this document.

## Non-scope

- No frontend, no endpoint/controller.
- No billing provider, no Stripe/Adyen/payment integration, no subscription/pricing logic.
- No monetary amounts and no floating-point money.
- No live enforcement in production request paths (quota check is advisory).
- No wiring into the live extraction / bot / channel paths (default: no live integration).
- No `TenantPlan` / `FeatureEntitlement` tables yet — deferred to 16C (foundation kept minimal).
- No order/quote/inventory/customer/approval/validation/external write of any kind.
- No AI call, no provider call, no external service dependency.

## Data model

### `usage_event` (append-only)

| Column | Type | Notes |
| --- | --- | --- |
| `id` | UUID | PK |
| `tenant_id` | UUID | required, tenant scope |
| `event_type` | VARCHAR(40) | `UsageEventType` |
| `metric_type` | VARCHAR(40) | `UsageMetricType` |
| `workload_type` | VARCHAR(40) | nullable; Stage 16A `AiWorkloadType` name |
| `model_tier` | VARCHAR(20) | nullable; Stage 16A `ModelTier` name |
| `units` | BIGINT | `>= 0`, `long` |
| `source` | VARCHAR(40) | `UsageSource` |
| `source_ref` | VARCHAR(180) | nullable internal id reference |
| `idempotency_key` | VARCHAR(180) | nullable; unique per tenant when present |
| `occurred_at` | TIMESTAMPTZ | event time |
| `created_at` | TIMESTAMPTZ | persisted time |
| `metadata_json` | JSONB | nullable, bounded, sanitized safe tokens only |

Indexes: `(tenant_id, metric_type, occurred_at DESC)`; partial unique
`(tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL`.

### `usage_counter` (aggregated)

| Column | Type | Notes |
| --- | --- | --- |
| `id` | UUID | PK |
| `tenant_id` | UUID | required |
| `metric_type` | VARCHAR(40) | `UsageMetricType` |
| `period_key` | VARCHAR(32) | deterministic, see below |
| `units_used` | BIGINT | `>= 0`, `long`, saturating |
| `created_at` / `updated_at` | TIMESTAMPTZ | |

Unique: `(tenant_id, metric_type, period_key)`.

### `quota_policy` (limit foundation)

| Column | Type | Notes |
| --- | --- | --- |
| `id` | UUID | PK |
| `tenant_id` | UUID | nullable (scope by tenant) |
| `plan_code` | VARCHAR(60) | nullable (scope by plan — future) |
| `metric_type` | VARCHAR(40) | `UsageMetricType` |
| `period_type` | VARCHAR(20) | `UsagePeriodType` |
| `limit_units` | BIGINT | `>= 0`, `long` count of units (never money) |
| `enforcement_mode` | VARCHAR(20) | `MONITOR` / `ENFORCE` (not enforced live in 16B) |
| `created_at` / `updated_at` | TIMESTAMPTZ | |

Migration: `resources/db/migration/V41__usage_metering_foundation.sql` (Postgres).
Tests run on H2 with Hibernate `create-drop` (Flyway disabled), so the schema there
derives from the JPA entity mappings; the migration provides Postgres parity.

## AI routing usage mapping

`recordAiRoutingDecision(tenantId, decision, sourceRef, idempotencyKey)` maps a
Stage 16A `AiRoutingDecision` to:

- `event_type` = `AI_ROUTING_DECISION`
- `metric_type` = `AI_INPUT_UNITS`
- `units` = `clampNonNegative(decision.estimatedInputUnits())`
- `source` = `AI_ROUTER`
- `workload_type` = `decision.workloadType().name()`
- `model_tier` = `decision.selectedTier().name()`
- `metadata_json` = `{ estimatedInputUnits, asyncRequired, humanReviewRequired, workloadType, modelTier, reasonCode, sourceRef }`
- `period_type` = `MONTH`

## Idempotency behavior

If `idempotencyKey` is supplied and a `usage_event` already exists for that
`(tenant_id, idempotency_key)`, recording is a no-op: the existing event id is
returned with `deduplicated=true`, `unitsRecorded=0`, and the counter is **not**
incremented a second time. A blank/null key always records a separate event.

## Quota decision behavior

`checkQuota` is advisory and never blocks a live path in Stage 16B:

- No policy → `allowed=true`, reason `NO_POLICY`, `limitUnits`/`remainingUnits` null.
- Policy and `used + additional <= limit` → `allowed=true`, reason `WITHIN_LIMIT`.
- Policy and over limit → `allowed=false`, reason `LIMIT_EXCEEDED` (in the returned
  decision only; no enforcement, no exception, no side effect).

## Security / privacy rules

Stored: `workloadType`, `modelTier`, `estimatedInputUnits`, `asyncRequired`,
`humanReviewRequired`, `reasonCode`, `sourceRef` (an internal id reference).

Never stored: raw customer message, raw document text, raw prompt, raw model
response, raw extracted line values, PII-heavy payloads, secrets, payment data,
connector credentials. The `UsageRecordingRequest` type deliberately carries no
free-form text/metadata map, so the service cannot be handed raw text. Tenant
isolation is enforced on every read/write by `tenant_id` scoping; no cross-tenant
aggregation is reachable.

## Overflow-safety rules

All accumulation uses `long` via `UsageMath`:

- `safeAdd(a, b)` saturates at `Long.MAX_VALUE` instead of wrapping.
- `clampNonNegative(value)` forces inputs into `[0, Long.MAX_VALUE]`.
- `remaining(limit, used)` never returns negative.

Counters and limits are `BIGINT`/`long` with `CHECK (>= 0)` constraints. Negative
or absurd inputs are normalized to `0`; large inputs saturate rather than overflow.

## Future stages

- **16C** — quota / entitlement guard (live, enforced checks; `TenantPlan` / `FeatureEntitlement`).
- **16D** — rate limiting / backpressure.
- **16E** — tenant-scoped cache.
- **16F** — runtime job foundation.

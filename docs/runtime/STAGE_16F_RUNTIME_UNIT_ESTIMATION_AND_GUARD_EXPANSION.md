# Stage 16F — Runtime Unit Estimation and Guard Expansion

## What 16F adds

1. A small, cheap `RuntimeUnitEstimator` abstraction (interface + `DefaultRuntimeUnitEstimator`)
   that estimates an operation's work size in usage units from **metadata already
   available at the boundary** — never by parsing documents, reading object storage,
   calling AI, or running extra queries.
2. The extraction guard no longer hardcodes `requestedUnits=1`; it goes through the
   estimator (which falls back to 1 because no cheap size metadata exists pre-extraction).
3. The runtime guard is wired into **two additional real high-cost boundaries**: bulk
   import activation and bulk reconciliation refresh.
4. An additive `RuntimeGuardService.enforceWithoutRate(...)` for operator/bulk operations
   (entitlement + quota, no rate throttling).

It preserves all 16A/16B/16C/16D/16E behavior. No billing, Stripe, Redis, admin UI,
frontend, or microservices.

## Estimator policy table

| Operation | Cheap signal (in priority order) | Units |
| --- | --- | --- |
| `AI_DOCUMENT_EXTRACTION` | page count | `pageCount` |
| | file size | `ceil(sizeBytes / 512KB)` |
| | line count | `ceil(lineCount / 25)` |
| | none | 1 |
| `BULK_IMPORT` | staged row count | `ceil(rowCount / 100)` |
| | file size | `ceil(sizeBytes / 1MB)` |
| | none | 1 |
| `RECONCILIATION_RUN` | candidate/pair count | `ceil(itemCount / 100)` |
| | none | 1 |
| `REPORT_GENERATED` | expected row count | `ceil(rowCount / 1000)` |
| | none | 1 |
| otherwise | message count | `messageCount` else 1 |

Every result is clamped to `[1, 100000]` and overflow-safe (ceiling division avoids
`Long` overflow; huge inputs saturate at the cap). The estimator never throws; missing or
negative metadata yields 1.

## Boundaries guarded (and why each is the narrowest correct seam)

| Boundary | Seam | Operation / Feature | Guard | Requested units |
| --- | --- | --- | --- | --- |
| AI document extraction | `ExtractionPipelineService.runNow(...)` (16D) | `AI_DOCUMENT_EXTRACTION` | `enforce` (entitlement → quota → **rate**) | estimator → 1 (no cheap metadata pre-extraction) |
| Bulk import activation | `ImportJobService.activate(...)` | `BULK_IMPORT` | `enforceWithoutRate` (entitlement → quota) | `ceil(job.totalRows / 100)` |
| Bulk reconciliation | `InventoryReconciliationService.refreshProjections()` | `RECONCILIATION_RUN` | `enforceWithoutRate` (entitlement → quota) | `ceil(pairCount / 100)` |

- **Extraction** is the single service method both extraction endpoints flow through; it
  is the high-frequency AI hot path, so it keeps full rate limiting.
- **`activate(...)`** is the one method that applies all staged rows to business tables —
  the expensive write. The guard is placed before the apply loop, so a denial applies
  nothing and the job stays `VALIDATED`. The staged row count is already stored
  (`job.getTotalRows()`), so estimation is free.
- **`refreshProjections()`** is the bulk projection that generates/updates many
  reconciliation cases in a loop. The guard runs after the operation's own first read (the
  distinct product/location pairs — not an extra estimation query) and before the loop, so
  a denial creates no case. The single-run `runInventoryReconciliation(...)` is left
  unguarded to avoid a per-pair guard storm during refresh.

## Why rate limiting is not applied at the two bulk boundaries

Activating an import or running a reconciliation refresh is a deliberate operator/scheduled
action that may legitimately burst (an operator importing several files in a minute is
normal — and exercised by existing tests). The 16C per-minute rate budgets were tuned for
high-frequency automated AI paths; throttling operator bulk actions at that rate would be a
regression. So these boundaries use `enforceWithoutRate` — entitlement + quota only — while
the AI extraction hot path keeps the full entitlement → quota → rate chain. Ordering
(entitlement before quota) is preserved everywhere; entitlement denial never reaches quota,
and quota denial never reaches rate.

## How requested units are calculated / where fallback 1 remains

- **Bulk import**: real, `> 1` for large imports (`ceil(rows/100)`); the
  `requestedUnitsScaleWithRowCount` test proves a 150-row import estimates 2 units and is
  quota-denied at limit 1 (a hardcoded 1 would have passed).
- **Reconciliation**: `ceil(pairs/100)`, ≥ 1.
- **Extraction**: estimator is invoked but **falls back to 1** — there is no cheap stored
  page/size/line count at the pre-extraction boundary, and counting would require
  loading/parsing content (forbidden). The `>1`-when-available behavior is proven by the
  estimator unit test; live propagation of estimated units into quota is proven at the
  extraction boundary with a forced-units stub.

## Security notes

- Estimator is O(1), pure, side-effect free: no external calls, no AI, no object-storage
  reads, no repository scans, no document parsing.
- All guard checks remain tenant-scoped; no cross-tenant reads. Denials throw before any
  business mutation / job application / case creation / provider call.
- Stable error mapping unchanged: feature → 403 `RUNTIME_FEATURE_NOT_AVAILABLE`, quota →
  403 `RUNTIME_QUOTA_EXCEEDED`, rate → 429 `RUNTIME_RATE_LIMITED` (extraction only). No plan
  internals are leaked in errors.

## Performance notes

- Estimation adds no query — it consumes counts the operation already has
  (`job.getTotalRows()`, the reconciliation pair list).
- The feature/quota lookups are the existing 16E indexed, bounded, tenant-scoped queries.
- No full scans, no heavy joins, no object storage reads added.

## Config wiring

`DefaultRuntimeUnitEstimator` is registered in `CoreConfiguration` as a
`@Bean @ConditionalOnMissingBean(RuntimeUnitEstimator.class)`, so tests can override it
(e.g. a `@Primary` configurable stub). The 16E persistent feature-policy bean was also
hardened to resolve via `ObjectProvider`, falling back to the permissive policy when the
entitlement repositories are absent (web/unit slices) — restoring `@WebMvcTest` contexts.

## Tests

- `DefaultRuntimeUnitEstimatorStage16FTest` (12) — policy, ≥1 contract, clamp, saturation.
- `ExtractionPipelineGuardStage16FTest` (5) — estimated units propagate to quota; fallback
  1; feature/quota/rate denial create no run; allowed run.
- `BulkImportGuardStage16FTest` (5) — allowed applies; feature/quota denial apply nothing;
  row-count-scaled units; tenant isolation.
- `ReconciliationGuardStage16FTest` (4) — allowed creates case; feature/quota denial create
  none; tenant isolation.
- Regression updated: `ImportJobServiceValidationTest`, `InventoryReconciliationServiceTest`,
  `TenantIsolationBoundaryTest` (constructor/import wiring). All 16A–16E, extraction,
  Stage 13/14/15, and controller web-slice tests pass.

## Limitations

- Extraction requested units remain 1 (no cheap pre-extraction size metadata).
- `REPORT_GENERATED` and message-classification estimation are implemented in the estimator
  but not yet wired to a live boundary (no clean existing seam this stage).
- Bulk boundaries are entitlement + quota only (rate deliberately omitted — see above).
- In-memory rate store unchanged (Redis still deferred).

## Recommended next stage (OP-CAP-16G)

- Thread cheap size metadata (page/line/byte counts) into the extraction boundary where it
  becomes available (e.g. from the stored document/processing-job record) so extraction
  units exceed 1.
- Wire the guard into report/export generation and AI explanation/summary generation using
  the already-present estimator policies (`REPORT_GENERATED`, message classification),
  adding the minimal enum values only when each boundary is real and tested.
- Consider a distributed `RateLimitStore` (Redis) for multi-node deployments and an
  admin/command surface to manage tenant plans and entitlements.

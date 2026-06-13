# OP-CAP-16G — Extraction Metadata Threading + Report/AI Explanation Guard Expansion

## What 16G adds

16G makes the existing runtime governance (16A–16F) **more accurate** and **more widely applied**,
without becoming a billing product or a platform rewrite.

1. **Extraction units can now exceed 1.** For an `INBOUND_DOCUMENT` extraction the already-stored
   file size is threaded into the estimator, so `AI_DOCUMENT_EXTRACTION` requested units scale with
   document size instead of always falling back to 1.
2. **Report/export generation is now guarded.** The pilot evidence report
   (`PilotShadowModeService.evidenceReport()`) is gated by entitlement + quota before the heavier
   metrics/breakdown aggregation runs.
3. **Advisory AI explanation/summary generation is now guarded.** `AiWorkService.createSuggestion(...)`
   is gated by the full runtime guard (entitlement → quota → rate) immediately before the AI provider
   call (`AiWorkProvider.generate(...)`).

All 16A–16F behavior, ordering, and public APIs are preserved.

## Metadata sources used for extraction units

The extraction boundary (`ExtractionPipelineService.runNow`) now threads **cheap, already-persisted**
metadata into `RuntimeUnitEstimateRequest.forDocumentExtraction(...)`:

| Source type        | Metadata used                                   | How it is obtained                                            |
|--------------------|-------------------------------------------------|---------------------------------------------------------------|
| `INBOUND_DOCUMENT` | `inbound_document.file_size_bytes`              | Tenant-scoped primary-key lookup (`findByIdAndTenantId`)      |
| anything else      | none                                            | estimator falls back to 1                                     |

Estimator arithmetic for `AI_DOCUMENT_EXTRACTION` is unchanged from 16F: page count → `ceil(bytes /
512KB)` → `ceil(lineCount / 25)` → 1. Only the **input** (file size) is now populated.

### Why page count is not used

Page count only becomes known **after** parse/OCR. Computing it pre-extraction would require opening
the file — which is explicitly forbidden for estimation. So page count remains a future input; today
the document path uses file size only. (The estimator's page-count branch still exists and is unit
tested, ready for a stage that has a cheaply-stored page count.)

## Why no parsing/OCR/AI is used for estimation

Estimation must stay O(1) and side-effect free. The only metadata reads are:

- A single tenant-scoped primary-key lookup of `inbound_document` (indexed; the same lookup the mock
  text provider already performs).
- A single tenant-scoped `COUNT` on `shadow_run` for the report path.
- An in-memory line count of the already-supplied advisory context string for the AI explanation
  path.

No file is opened, no OCR is run, no AI/provider is called, no object storage is read, and no
full-table scan or heavy join is performed for estimation.

## Guarded report/export seam

`PilotShadowModeService.evidenceReport()` — the narrowest service-layer seam that starts the heavier
pilot evidence report aggregation (loads shadow runs + corrections, computes metrics/breakdown).

- Feature: `RuntimeFeatureType.REPORT_EXPORT` (new).
- Operation: `RuntimeOperationType.REPORT_GENERATED` (pre-existing).
- Guard mode: **entitlement + quota only** (`enforceWithoutRate`) — operator-initiated and
  low-frequency, so the per-minute rate window is not consumed (consistent with the 16F bulk/operator
  rule).
- Requested units: from a cheap tenant-scoped `COUNT(shadow_run)` via
  `RuntimeUnitEstimateRequest.forReport(...)` → `ceil(rowCount / 1000)`, fallback 1.
- Quota dimension: `UsageMetricType.REPORT_GENERATED` (new), mapped in `EndpointWeightPolicy`.

The guard runs **before** any metrics/breakdown query, so a disabled entitlement or exhausted quota
returns a stable 403 and no report work runs.

## Guarded AI explanation/summary seam

`AiWorkService.createSuggestion(...)` — the narrowest seam immediately before
`AiWorkProvider.generate(...)`. The provider is the swappable AI contract (today a deterministic
implementation; a real LLM provider can replace it behind the same interface), so this is the correct
pre-provider boundary for `VALIDATION_EXPLANATION` / `REQUEST_SUMMARY` and the other advisory work
types.

- Feature: `RuntimeFeatureType.AI_VALIDATION_EXPLANATION` (new).
- Operation: `RuntimeOperationType.AI_VALIDATION_EXPLANATION` (new).
- Guard mode: **full** (`enforce`: entitlement → quota → rate) — preferred for AI/provider calls.
- Requested units: from the in-memory context line count via
  `RuntimeUnitEstimateRequest.forExplanation(...)` → `ceil(lineCount / 25)` → message count → 1.
- Quota dimension: `UsageMetricType.AI_INPUT_UNITS` (shared with extraction — same consumption
  metric).
- The guard runs **after** the idempotency short-circuit (a retried idempotency key returns the
  existing suggestion without consuming guard budget) and **before** `provider.generate(...)`, so no
  provider call and no suggestion row occur on any denial.

## Paths searched but not guarded, and why

- **`Stage8ValueAnalyticsController` / `BusinessValueAnalyticsService.export()`** — a second real
  report/export seam. Not guarded in 16G to keep scope bounded; one report seam (pilot evidence
  report) is sufficient to prove the report-guard pattern. Candidate for a later stage.
- **`WorkspaceSummaryService.summary()`** — only cheap aggregate `COUNT`s; not heavy report
  generation, so guarding it would be parasite complexity.
- **`PilotShadowModeService.metrics()` / `exceptionBreakdown()`** — internal aggregates reused by the
  report; the report seam guards the entry point, so guarding the helpers too would double-charge.
- **Stage 16A workload classification / deterministic AI work content** — `AiWorkloadClassifier` and
  the deterministic generation logic are O(1) local rule-based code with no provider call, so no
  artificial guard was added. The guard sits at the provider-call seam instead.
- **Channel/bot classification** — no separate AI/provider classification call distinct from the
  already-guarded extraction/bot-runtime paths; not double-guarded.

## Estimator policy after 16G

| Operation                   | Units policy (first match wins)                                     | Fallback |
|-----------------------------|---------------------------------------------------------------------|----------|
| `AI_DOCUMENT_EXTRACTION`    | page count → `ceil(bytes / 512KB)` → `ceil(lineCount / 25)`         | 1        |
| `BULK_IMPORT`               | `ceil(rowCount / 100)` → `ceil(bytes / 1MB)`                        | 1        |
| `RECONCILIATION_RUN`        | `ceil(itemCount / 100)`                                             | 1        |
| `REPORT_GENERATED`          | `ceil(rowCount / 1000)`                                             | 1        |
| `AI_VALIDATION_EXPLANATION` | `ceil(lineCount / 25)` → message/item count                         | 1        |
| otherwise                   | message count                                                       | 1        |

All results clamped to `[1, MAX_UNITS]` and overflow-safe.

## Guard policy after 16G

| Boundary                                   | Feature                       | Operation                   | Mode (ordering)                  |
|--------------------------------------------|-------------------------------|-----------------------------|----------------------------------|
| Extraction (`runNow`)                      | `AI_DOCUMENT_EXTRACTION`      | `AI_DOCUMENT_EXTRACTION`    | full: entitlement → quota → rate |
| Bulk import activation (16F)               | `BULK_IMPORT`                 | `BULK_IMPORT`               | entitlement → quota              |
| Reconciliation projection (16F)            | `RECONCILIATION_RUN`          | `RECONCILIATION_RUN`        | entitlement → quota              |
| Pilot evidence report (`evidenceReport`)   | `REPORT_EXPORT`               | `REPORT_GENERATED`          | entitlement → quota              |
| AI explanation (`createSuggestion`)        | `AI_VALIDATION_EXPLANATION`   | `AI_VALIDATION_EXPLANATION` | full: entitlement → quota → rate |

## Security notes

- Estimation performs no external I/O, no parsing/OCR, no AI/provider call, no object-storage read,
  no full-table scan, no heavy join.
- All metadata reads are tenant-scoped (`findByIdAndTenantId`, `countByTenantId`,
  `TenantContext.requireTenantId()`); cross-tenant reads are not reachable. Tenant isolation is proven
  by test for each new boundary.
- Denials happen **before** expensive work and before any AI/provider call or report aggregation.
- No plan internals are leaked — only stable reason-code tokens flow out.
- No raw SQL string concatenation; only Spring Data derived queries.
- No direct AI/business-table write is introduced; the AI guard sits before an advisory-only path.

## Performance notes

- Estimation remains O(1) (a clamp + ceiling division on already-known integers).
- Metadata lookups are bounded by `tenant_id + id` (indexed PK) or a tenant-scoped `COUNT`.
- The AI line count is one O(n) scan over an already-in-memory, bounded context string — no I/O.
- No report precomputation is performed solely for the guard; the report's own aggregation is unchanged
  and runs only after the guard allows.

## Limitations

- Document page count is still not used pre-extraction (only becomes known after parse/OCR).
- Only one report/export seam (pilot evidence report) is guarded; `Stage8` ROI export remains a
  candidate.
- The rate-limit store remains in-memory (no Redis).
- The AI provider remains the deterministic implementation; the guard is provider-agnostic and is
  ready for a real LLM provider.

## Recommended OP-CAP-16H scope

- Guard the second report/export seam (`Stage8ValueAnalyticsService.export()`), reusing
  `REPORT_EXPORT` / `REPORT_GENERATED`.
- Thread a cheaply-stored page count (if a future intake stage persists one) into the extraction
  estimate.
- Consider a distributed `RateLimitStore` (e.g. Redis) behind the existing `RateLimitStore`
  interface — no service changes required.

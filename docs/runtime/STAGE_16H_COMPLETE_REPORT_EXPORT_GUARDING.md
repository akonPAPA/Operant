# OP-CAP-16H — Complete Report/Export Runtime Guarding

## What 16H adds

16H is the bounded completion of report/export runtime protection started in 16G. It does **not**
make reports better — it makes the remaining real report/export generation seam governed by the same
runtime feature/quota protection.

1. **The Stage 8 pilot ROI report export is now guarded.**
   `BusinessValueAnalyticsService.export()` (served by `GET /api/stage8/value/export` via
   `Stage8ValueAnalyticsController`) is gated by entitlement + quota **before** its heavier
   multi-source metrics aggregation runs.
2. It reuses the existing 16G `RuntimeFeatureType.REPORT_EXPORT` feature and
   `RuntimeOperationType.REPORT_GENERATED` operation, the existing `RuntimeUnitEstimator` /
   `RuntimeUnitEstimateRequest.forReport(...)`, and the existing `UsageMetricType.REPORT_GENERATED`
   quota dimension. No new runtime API, feature, operation, or metric is introduced.

All 16A–16G behavior, ordering, and public APIs are preserved.

## Report/export boundaries inspected

| Surface                                                   | Classification                                                     |
|-----------------------------------------------------------|--------------------------------------------------------------------|
| `BusinessValueAnalyticsService.export()`                  | **Real, heavy report/export** — full ROI report from many sources. **Guarded.** |
| `PilotShadowModeService.evidenceReport()`                 | Real report — **already guarded in 16G** (not re-touched).         |
| `BusinessValueAnalyticsService.summary()/leakage()/productivity()` | Operator dashboard reads, not export deliverables — not guarded (see below). |
| `BusinessValueAnalyticsService.metrics()` (private)       | Internal aggregate reused by `export()` — guarding it would double-charge. |
| `PilotShadowModeService.metrics()/exceptionBreakdown()`   | Internal aggregates reused by the 16G report seam — not re-guarded (would double-charge). |
| `CsvIntegrationAdapter`                                   | A Stage 12 inbound business-system *integration adapter* (CSV provider type), **not** a report/export generator — not a report seam. |
| `WorkspaceSummaryService.summary()` (per 16G notes)       | Cheap aggregate COUNTs, not heavy report generation — not guarded. |

A repository/source sweep for `export`, `report`, `csv`, `download`, `evidenceReport`,
`ValueAnalytics`, analytics controllers/services, reconciliation/audit/validation-review export, and
demo/evidence report generation found **no other real heavy report/export generation seam** beyond
`export()` and the already-guarded `evidenceReport()`.

## Boundary guarded and why it is the narrowest correct seam

`BusinessValueAnalyticsService.export()` is the service-layer entry point that produces the
`Stage8PilotRoiReportResponse`. It is the narrowest correct seam because:

- The guard sits at the top of the public method, **before** `metrics(tenantId, assumptions)` (which
  loads exception cases, draft quotes/orders + lines, discount/margin results, reconciliation cases,
  channel messages and inbound documents) and before the `CommerceAnalyticsService.stage8CommandCenter()`
  aggregation. A disabled entitlement or exhausted quota throws a stable mapped 403 and **no report
  work runs**.
- The sibling read methods (`summary`/`leakage`/`productivity`) call `metrics()` too, but `export()`
  is the only one that assembles and returns the full pilot ROI report deliverable. Guarding the
  shared private `metrics()` helper would double-charge the dashboard reads and is rejected.
- The controller is a thin pass-through, so the service method — not the controller — is the
  authoritative business seam (consistent with the 16G `evidenceReport()` placement).

## Boundaries not guarded and why

- **`summary()` / `leakage()` / `productivity()`** — operator dashboard reads, not export
  deliverables. They are routine, high-frequency screen loads; guarding them as `REPORT_EXPORT`
  would mis-charge ordinary navigation and regress the analytics dashboard. They remain ungoverned by
  the report guard (they are still tenant-scoped and read-only).
- **`metrics()` (private)** — internal aggregate reused by all four methods; guarding it would
  double-charge and would also (incorrectly) gate the dashboard reads.
- **`PilotShadowModeService.metrics()` / `exceptionBreakdown()`** — internal aggregates reused by the
  16G report seam; the report entry point already guards them.
- **`CsvIntegrationAdapter`** — a Stage 12 business-system integration provider type, not a
  report/export generator; out of scope for report guarding.

## Unit estimation policy for the guarded export

- `export()` passes a **cheap tenant-scoped `COUNT(exception_case)`**
  (`ExceptionCaseRepository.countByTenantId(tenantId)`) as `knownRowCount` into
  `RuntimeUnitEstimateRequest.forReport(...)`.
- The estimator computes `ceil(rowCount / 1000)`, clamped to `[1, MAX_UNITS]`, falling back to **1**
  when the count is 0.
- Exception/review cases are the central report-driving entity (they feed the exception-cause
  breakdown, the review/draft cycle metrics, and the blocked-attempt signal), so their count is a
  representative, cheap, index-friendly proxy for the report's row volume.
- **No report rows are loaded for the estimate.** The estimate is a single indexed `COUNT`; the
  report's own multi-source aggregation runs only after the guard allows.

This is consistent with the 16G estimator policy table (`REPORT_GENERATED` → `ceil(rowCount / 1000)`,
fallback 1) and adds no new estimator branch.

## Why `enforceWithoutRate` is used (not full `enforce`)

`export()` is an **operator-initiated, low-frequency** report export. Per the 16F/16G operator-report
rule, rate limiting is reserved for high-frequency automated hot paths (e.g. AI extraction). An
operator generating an ROI report — possibly repeatedly during a demo or an admin review — may
legitimately burst, so the per-minute rate window must not throttle it. Entitlement and quota are
**still enforced** (ordering: entitlement → quota). This matches the 16G `evidenceReport()` decision
exactly. The Stage 8 export is not automated or externally callable at high frequency, so full
`enforce(...)` with rate is not warranted.

## Security notes

- Estimation performs no external I/O, no parsing/OCR, no AI/provider call, no object-storage read,
  no full-table scan, no heavy join — only one tenant-scoped indexed `COUNT`.
- All reads are tenant-scoped (`TenantContext.requireTenantId()`, `countByTenantId`); cross-tenant
  reads are not reachable. Tenant isolation is proven by test (`tenantADenialDoesNotBlockTenantB`).
- Denial happens **before** the report aggregation; no report deliverable is built on denial.
- No plan internals are leaked — only stable reason-code tokens flow out via the mapped 403.
- No raw SQL string concatenation; `countByTenantId` is a Spring Data derived query.
- No AI/provider call, no external write, and no business mutation is introduced; the guard sits in
  front of a read-only advisory report.

## Performance notes

- Guard estimation is O(1): one ceiling division on an already-known integer, plus one tenant-scoped
  indexed `COUNT(exception_case)`.
- No report precomputation is performed solely for the guard; the report's own aggregation is
  unchanged and runs only after the guard allows.
- No additional rate limiting is imposed on the low-frequency operator report (justified above).

## Limitations

- The unit estimate uses the exception/review case count as the report-size proxy; it does not sum
  every source list (channel messages, inbound documents, drafts, etc.) to avoid extra queries. For
  realistic tenant volumes both resolve to 1 unit, so this is an accuracy refinement, not a
  correctness gap.
- The rate-limit store remains in-memory (no Redis) — unchanged from 16C–16G.
- No admin command/API for tenant plans/entitlements and no billing/subscription/payment UI exist
  (explicitly out of scope).

## Recommended OP-CAP-16I scope

- Consider a distributed `RateLimitStore` (e.g. Redis) behind the existing `RateLimitStore`
  interface — no service changes required.
- Add a minimal read-only admin/API surface for tenant plans and entitlements (governance
  visibility), without billing/payment integration.
- Thread a cheaply-stored document page count (if a future intake stage persists one) into the
  extraction estimate, retiring the file-size-only fallback.
- If a future stage adds a high-frequency automated or externally-callable report/export path, guard
  it with full `enforce(...)` (entitlement → quota → rate).

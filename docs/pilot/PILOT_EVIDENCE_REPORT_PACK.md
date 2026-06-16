# Pilot Evidence Report Pack (OP-CAP-11G)

This document describes the **implemented** Pilot Evidence Report — a design-partner /
investor-ready summary built by composing the existing pilot shadow-mode metrics. It does
**not** add a new pilot subsystem and does **not** introduce new metric logic.

It builds directly on:

- `docs/pilot/PILOT_SHADOW_MODE_ROI_READINESS.md` — the ROI readiness metrics layer (OP-CAP-11F).
- `docs/pilot/SHADOW_MODE_SPEC.md` — what shadow mode means.
- `docs/pilot/PILOT_METRICS_SPEC.md` — the full metric catalogue and calculations.
- `docs/pilot/CORRECTION_TRACKING_CONTRACT.md` — human correction tracking contract.

See those documents for the underlying definitions; they are not duplicated here.

## What the report is

A single, structured, tenant-scoped report that composes the existing
`PilotShadowModeService.metrics()` and `exceptionBreakdown()` into one evidence pack:

- ROI evidence — estimated minutes saved and estimated cost saved (from the tenant's
  existing `RoiAssumptionsService` rate; no external call);
- cycle-time evidence — average manual baseline vs average assisted minutes;
- exception category breakdown and the top exception categories;
- automation-candidate count and review-required workload;
- human correction rate and totals;
- deterministic **readiness signals** (sample size, correction rate, automation candidates,
  review workload, estimated time saved), each with a plain assessment label;
- a fixed **safety statement** and **limitations** list.

It is **evidence of shadow-mode and pilot readiness, not a guarantee of production ROI.**

## API

- `GET /api/v1/pilot/evidence-report` — requires `ANALYTICS_READ` (enforced by
  `ApiPermissionInterceptor` on the `/api/v1/pilot` prefix, re-validated per request).
  Tenant-scoped via `TenantContext`.

Response is the `PilotEvidenceReport` DTO (`Stage10BDtos`). It is deterministic, with
fixed rounding (minutes 2dp, cost 2dp, percentages 4dp, signal percentages 1dp) and
defined empty-state behaviour (zero runs / corrections / categories → zeroed figures,
empty lists, `NO_DATA` readiness assessments, but the safety statement and limitations are
always present).

### Intentionally excluded (safety)

The report API never returns raw prediction payload JSON, correction before/after JSON,
object-storage keys/internals, secrets, tokens, credentials, or customer-sensitive raw
text. This is enforced by contract (`PilotEvidenceReportDtoSafetyTest` reflects over the
DTO graph and fails on any forbidden field name).

### CSV export

A CSV variant (`GET /api/v1/pilot/evidence-report.csv`) is **not** included in this slice:
the repository has no existing safe REST CSV-export convention, and adding one is out of
scope. This is a documented limitation, not an omission — operators can use the
print-friendly UI below to export a PDF.

## UI

- `/pilot-readiness/evidence-report` — a read-only, print-friendly page (browser Print →
  PDF, via a small `@media print` block) showing the ROI summary, cycle-time summary,
  exception category table, automation/review workload, readiness signals, and a
  safety/limitations panel. Linked from `/pilot-readiness` and the primary navigation.
- No charting library or new framework was added; it reuses the existing dashboard shell,
  panel/table classes, and API-client conventions.

## How figures are calculated

- **Human correction rate** = `corrected / reviewed` (4dp, HALF_UP).
- **Estimated minutes saved** = `Σ max(0, manualBaseline − assisted)` over runs with a
  baseline.
- **Estimated cost saved** = `estimatedMinutesSaved / 60 × hourlyCost`, where `hourlyCost`
  and currency come from the tenant's `RoiAssumptions` (safe local/demo default $45.00/hr,
  USD).
- **Top exception categories** = the categories sorted by count (descending, then category
  name for stable ties), limited to three.

All values are pure functions of stored `shadow_run` / `human_correction` rows and the
tenant ROI rate, so the report is fully reproducible and unit-tested.

## Safety posture

*AI suggests, rules validate, humans approve, the backend writes, audit records.*
Shadow-mode results are advisory (`MOCK_ONLY`) and never auto-approve quotes/orders or
trigger ERP/1C/connector writes. No real AI provider is invoked. All figures are
tenant-scoped. The report supports design-partner conversations with credible, auditable,
non-raw evidence — without claiming or enabling production automation.

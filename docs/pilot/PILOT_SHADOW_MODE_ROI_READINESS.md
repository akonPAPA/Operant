# Pilot Shadow-Mode ROI Readiness (OP-CAP-11F)

This document describes the **implemented** pilot evidence/measurement layer that lets
OrderPilot prove value during design-partner pilots without enabling unsafe production
automation. It is the implementation-facing companion to the conceptual specs:

- `docs/pilot/SHADOW_MODE_SPEC.md` — what shadow mode means and the workflow.
- `docs/pilot/PILOT_METRICS_SPEC.md` — the full metric catalogue and calculations.
- `docs/pilot/CORRECTION_TRACKING_CONTRACT.md` — human correction tracking contract.

This slice **extends the existing Stage 10B shadow-mode model** (`shadow_run`,
`human_correction`, `PilotShadowModeService`, `PilotController`, `Stage10BDtos`). It does
**not** introduce a parallel pilot subsystem.

## What shadow mode means here

OrderPilot records advisory predictions ("shadow runs") for real-like pilot inputs while
humans stay authoritative. A shadow run never becomes an approved quote/order and never
triggers an external write. A human reviewer records the correction outcome. From the
accumulated runs and corrections OrderPilot derives tenant-scoped ROI evidence.

Provider mode is fixed to `MOCK_ONLY` (DB check constraint `ck_shadow_run_mock_only`).
No real AI provider is called; there are no API keys or provider payloads.

## What is measured

Recorded per shadow run (`shadow_run` table), all tenant-scoped:

- source type + source id (inbound document, channel message, RFQ conversion, draft
  quote/order, validation run, or other existing source entity);
- prediction type and confidence score (band-friendly);
- processing status (`RECORDED` → `ACCEPTED` / `CORRECTED` / `REJECTED`);
- **exception category** (label only, e.g. `OUT_OF_STOCK_SUBSTITUTE`, `MARGIN_VIOLATION`);
- **manual baseline minutes** and **assisted processing minutes** (cycle-time inputs);
- **automation candidate** flag and **review required** flag;
- prediction/review timestamps (`created_at`, `reviewed_at`).

Human corrections (`human_correction` table) record the correction type, the correcting
user, before/after presence, an optional reason, and timestamp.

### Not stored

Pilot metric records never store raw document text, raw message text, connector
credentials, provider payloads, unrestricted AI output, object-storage keys, or secrets.
The prediction/correction payload columns hold only controlled `MOCK_ONLY` fixtures and
are **never echoed** in API responses (see "Safe API surface" below).

## Derived ROI evidence

`GET /api/v1/pilot/metrics` returns a deterministic tenant-scoped summary:

- total reviewed items, accepted/corrected/rejected counts;
- **human correction rate** = `corrected / reviewed` (4dp, HALF_UP);
- average confidence;
- average manual baseline minutes, average assisted minutes;
- **estimated minutes saved** = `Σ max(0, baseline − assisted)` over runs with a baseline;
- **estimated cost saved** = `estimatedMinutesSaved / 60 × hourlyCost`, where `hourlyCost`
  and currency come from the tenant's existing **ROI assumptions**
  (`RoiAssumptionsService`, safe local/demo default $45.00/hr, USD — no external call);
- automation candidate count, review required count;
- prediction-type and correction-type breakdowns.

`GET /api/v1/pilot/metrics/exceptions` returns the **exception category breakdown**:
category, count, and share of categorized runs (deterministic, payload-free).

All calculations are pure functions of stored rows and the tenant ROI rate, so they are
fully reproducible and unit-tested (`PilotShadowModeServiceTest`).

## Safe API surface

- Reads (`GET /api/v1/pilot/...`) require `ANALYTICS_READ`; recording shadow runs and human
  corrections requires `REVIEW_ACTION`. Enforced by `ApiPermissionInterceptor` and
  re-validated on every request. (Previously the `/api/v1/pilot` prefix was unguarded;
  this slice closes that gap.)
- Responses expose **presence flags** (`hasPredictionPayload`, `hasBeforePayload`,
  `hasAfterPayload`) instead of raw payload JSON.
- No object-storage keys, secrets, or cross-tenant data are returned.

## What is explicitly NOT enabled

- No production ERP/1C/accounting/inventory write execution.
- No real external connector network calls.
- No real AI provider onboarding or API keys.
- Shadow-mode results never auto-approve quotes/orders or auto-execute external writes.
- Human corrections and approvals remain explicit and audited
  (`PILOT_SHADOW_RUN_RECORDED`, `PILOT_HUMAN_CORRECTION_RECORDED`).

## How this supports design-partner conversations

The summary and exception breakdown give a credible, tenant-isolated, auditable evidence
base — correction rate trend, estimated minutes/cost saved, automation-candidate share,
and the top exception categories driving rework — without claiming or enabling production
automation. It demonstrates measured value under the controlled write-path rules: *AI
suggests, rules validate, humans approve, the backend writes, audit records.*

## Frontend

A read-only **Pilot Readiness** dashboard page (`/pilot-readiness`) surfaces the summary
cards (reviewed items, correction rate, estimated minutes saved, review-required count,
top exception category) and the exception breakdown table, with an explicit note that
shadow-mode metrics are advisory and do not execute external writes.

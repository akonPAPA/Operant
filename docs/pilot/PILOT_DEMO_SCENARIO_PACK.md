# Pilot Demo Scenario Pack (OP-CAP-11H)

## Purpose

A read-only, tenant-scoped, deterministic **demo-readiness** layer that turns the pilot
evidence report into a coherent set of investor / design-partner demo scenarios. It makes
the core Core v1 workflows visible and honestly scored, without executing any business
action, calling any AI provider, or writing to any external system.

It composes the existing pilot evidence report — it does **not** add a parallel subsystem,
new metric logic, persistence, or mutations.

Related docs (not duplicated here):

- `docs/pilot/PILOT_EVIDENCE_REPORT_PACK.md` — the evidence report this pack composes.
- `docs/pilot/PILOT_SHADOW_MODE_ROI_READINESS.md` — the underlying ROI metrics.
- `docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md` — the canonical investor demo script
  (the live flows this scenario pack maps onto).

## Scenarios

| Code | Title | Channel | Honest ceiling |
|---|---|---|---|
| `TELEGRAM_RFQ_SUBSTITUTION` | Telegram RFQ with substitution | TELEGRAM | PARTIAL → READY_FOR_SCRIPTED_DEMO with substitution evidence |
| `PDF_PO_EXCEPTION` | PDF purchase order with validation/exception handling | FILE_UPLOAD | PARTIAL → READY_FOR_SCRIPTED_DEMO with extraction + exception evidence |
| `DISCOUNT_MARGIN_GUARDRAIL` | Discount/margin guardrail requiring approval | INTERNAL | PARTIAL → READY_FOR_SCRIPTED_DEMO with margin/discount exception evidence |
| `INVENTORY_MISMATCH` | Inventory mismatch / reconciliation discrepancy | INTERNAL | PARTIAL (reconciliation exists but not yet linked to pilot evidence) |
| `BAD_AI_OUTPUT_REJECTED` | Bad AI output / unsafe input rejection | INTERNAL | READY_FOR_SCRIPTED_DEMO (deterministic safety guards; no seeded data needed) |

Readiness values: `NOT_AVAILABLE`, `BLOCKED`, `PARTIAL`, `READY_FOR_SCRIPTED_DEMO`.
`READY_FOR_SCRIPTED_DEMO` is the **honest ceiling** for this slice — readiness never reaches
100% and never claims production completeness.

## What is ready vs partial

- **Code-level safety demo (`BAD_AI_OUTPUT_REJECTED`)** is ready for a scripted demo with no
  seeded data: it rests on `ExtractionSchemaValidator`, `PromptInjectionGuardService`,
  `AiOutputSanitizer`, and the pilot DTO safety tests.
- **Evidence-dependent scenarios** (Telegram, PDF, discount/margin) are `PARTIAL` until the
  tenant has the relevant pilot shadow evidence (substitution / extraction+exception /
  margin-violation), after which they deterministically become `READY_FOR_SCRIPTED_DEMO`.
- **Inventory mismatch** is honestly `PARTIAL`: `InventoryReconciliationService` +
  `ReconciliationCase` exist and can be shown live at `/reconciliation`, but reconciliation
  outcomes are **not yet** composed into the pilot evidence report.

## Data requirements

Readiness is derived from the tenant's pilot evidence report. To raise evidence-dependent
scenarios to `READY_FOR_SCRIPTED_DEMO`, record shadow runs (via the guarded
`POST /api/v1/pilot/shadow-runs`) carrying the relevant prediction types / exception
categories (e.g. `SUBSTITUTION` / `OUT_OF_STOCK_SUBSTITUTE`, `EXTRACTION` with exceptions,
`MARGIN_VIOLATION`). A synthetic fixture is available at
`apps/core-api/src/test/resources/demo/core-v1-demo/pilot-shadow-demo.json`.

A persistent demo-seed table for scenarios is **not** implemented in this slice — this is a
read-only scenario/readiness layer (see limitations).

## API

- `GET /api/v1/pilot/demo-scenarios` — requires `ANALYTICS_READ` (same guard as pilot
  analytics/evidence reporting, enforced by `ApiPermissionInterceptor` on the `/api/v1/pilot`
  prefix). Tenant-scoped, deterministic, stable scenario ordering. Returns the
  `PilotDemoScenarioPackResponse` DTO (`Stage10BDtos`).

The response carries only structured, non-raw fields. It never returns raw prediction or
correction payloads, object-storage keys, secrets, tokens, prompts, connector credentials,
or raw customer text (enforced by `PilotEvidenceReportDtoSafetyTest`).

## Exact dashboard paths

- `/pilot-readiness` — pilot readiness overview (links to demo scenarios).
- `/pilot-readiness/evidence-report` — print-friendly evidence report (links to demo scenarios).
- `/pilot-readiness/demo-scenarios` — this scenario pack (scenario cards with readiness,
  capabilities, evidence, gaps, safety boundaries, and suggested demo routes).

## Safety boundaries

- AI output is advisory only; it never writes business or master data.
- No ERP/1C/connector writes occur in any scenario.
- Risky actions require deterministic validation and human approval.
- All data is tenant-scoped and audit-oriented.
- This slice is read-only: no mutation endpoint and no autonomous business action were added.

### No-AI-autonomy statement

No real AI provider is invoked. Shadow-mode predictions are advisory (`MOCK_ONLY`) and never
auto-approve quotes/orders.

### No-ERP-write statement

No ERP/1C/connector write execution exists in this slice; the scenario pack is purely a
readiness view.

## How this relates to the evidence report

The scenario pack is composed from `PilotShadowModeService.evidenceReport()` (OP-CAP-11G).
It maps the report's metrics, exception categories, and readiness signals onto per-scenario
readiness. No metric is recomputed or duplicated.

## Known limitations

- Telegram and PDF flows are local/dev and mock-extraction only; not production-complete.
- Inventory reconciliation is not yet recorded as pilot shadow evidence.
- No CSV export and no persistent scenario-seed table in this slice.
- Readiness is honest and capped at `READY_FOR_SCRIPTED_DEMO`.

## Operator / investor talking points

- "Every scenario is scored honestly — we show what is ready for a scripted demo and what
  still needs seeded evidence or production wiring."
- "The product is safe by construction: AI suggests, rules validate, humans approve, the
  backend writes, audit records."
- "Bad AI output and hostile input are rejected before they can touch business data."

## What remains before a paid design-partner pilot

- Production OCR/LLM extraction (replace mock extraction).
- Production outbound Telegram/WhatsApp messaging.
- Composing reconciliation outcomes into pilot evidence.
- Seeded, repeatable pilot datasets per design partner.
- Controlled, audited external-write (ChangeRequest) execution for approved drafts.

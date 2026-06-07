# Pilot Scripted Demo Dataset (OP-CAP-11I)

## Purpose

A realistic, **fake**, deterministic demo dataset and scripted scenario pack for OrderPilot
Core v1. It lets the product demonstrate real-looking distributor workflows — and makes the
OP-CAP-11G evidence report and OP-CAP-11H demo scenario pack meaningful — **without using
production data or external systems**.

This slice is fixtures + docs + tests. It does **not** add a production seed endpoint, a new
business subsystem, real provider integrations, or any external/AI/ERP write.

Related docs:

- `docs/pilot/PILOT_EVIDENCE_REPORT_PACK.md` — the OP-CAP-11G evidence report this dataset feeds.
- `docs/pilot/PILOT_DEMO_SCENARIO_PACK.md` — the OP-CAP-11H scenario pack these scenarios map to.
- `docs/pilot/PILOT_SHADOW_MODE_ROI_READINESS.md` — the underlying ROI metrics.
- `docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md` — the canonical investor demo script.

## Where the dataset lives

All fixtures live under the existing demo-fixture convention:

```
apps/core-api/src/test/resources/demo/core-v1-demo/
```

They are loaded for tests/local demo by the existing `DemoDataService` test utility
(`apps/core-api/src/test/java/com/orderpilot/demo/DemoDataService.java`). There is **no**
production-exposed loader and **no** REST seed endpoint.

## Fake tenant

| Field | Value |
|---|---|
| Slug | `orderpilot-demo-parts-distributor` |
| Legal name | OrderPilot Demo Parts Distributor |
| Status | ACTIVE |
| Location | Almaty Main Warehouse (`WH-ALM`), Almaty, KZ |

(Stable demo keys — slug, location code, customer code, SKUs — are used for idempotency.)

## Fake users / roles (demo narrative)

The scripted scenarios reference these operator roles. They describe who acts in the demo;
they are not seeded credentials and carry no passwords:

- Owner / admin
- Sales operator
- Inventory / operations manager
- Sales manager / approver

## Fake customers

| Code | Name | Notes |
|---|---|---|
| `CUST-001` | Steppe Logistics LLP (Steppe Logistics) | Wholesale, repeat customer; accepts the aftermarket Camry brake pad; **blocks** the budget SteppeLine substitute. |

## Fake products / SKUs / aliases

| SKU | Name | Role |
|---|---|---|
| `PAD-OE-04465` | Toyota Camry 2018 OEM Front Brake Pad Set | Original; **out of stock** in the demo; customer alias `brake pads for Toyota Camry 2018`. |
| `PAD-SUB-ADV` | Advantage Ceramic Brake Pad Set (RoadMax) | Compatible substitute; **accepted**; in stock. |
| `PAD-SUB-ECON` | Economy Brake Pad Set (SteppeLine) | Compatible substitute; **blocked** for this customer; requires approval. |

Compatibility (fitment) and substitute relations for Toyota Camry 2018 are seeded by
`DemoDataService.seedStage11C(...)`.

## Inventory setup

| SKU | On hand / available | Note |
|---|---|---|
| `PAD-OE-04465` | 0 / 0 | Out of stock (drives the substitution story). |
| `PAD-SUB-ADV` | 80 / 75 | In stock substitute. |
| `PAD-SUB-ECON` | 4 / 4 | Low stock, blocked substitute. |

Inventory movements (`inventory-movements-demo.json`): opening 150, sale 34, actual count 100
for `PAD-OE-04465` at `WH-ALM` — the basis for the reconciliation mismatch.

## Pricing / discount / margin setup

- Customer/list price rules are seeded per product (`PAD-OE-04465`, `PAD-SUB-ADV`, `PAD-SUB-ECON`).
- The discount/margin guardrail scenario scripts a 35% discount on `PAD-SUB-ADV` (cost 25.00)
  that drops the line margin below the tenant margin threshold and therefore requires manager
  approval. The concrete threshold is tenant configuration, not a fixture constant.

## Substitution setup

- `PAD-SUB-ADV` is an accepted compatible alternative for `PAD-OE-04465`.
- `PAD-SUB-ECON` is a blocked substitute for `CUST-001` (requires approval).

## Pilot evidence setup

`pilot-shadow-demo.json` carries five synthetic, MOCK_ONLY shadow runs (structured evidence
only — no raw document/message payloads, no secrets). Seeding them makes the OP-CAP-11G
evidence report non-empty and moves selected OP-CAP-11H scenarios from `PARTIAL` to
`READY_FOR_SCRIPTED_DEMO`. Readiness stays honest and capped: `READY_FOR_SCRIPTED_DEMO` is the
ceiling — it never reaches 100% and never claims production completeness.

## Scenario fixture table

Defined in `scripted-scenarios-demo.json`, keyed to the OP-CAP-11H scenario codes:

| Code | Channel | Input fixture | Demo route | Evidence impact |
|---|---|---|---|---|
| `TELEGRAM_RFQ_SUBSTITUTION` | TELEGRAM | `telegram-rfq-demo.json` | `/bot-conversations` | SUBSTITUTION / OUT_OF_STOCK_SUBSTITUTE → READY_FOR_SCRIPTED_DEMO |
| `PDF_PO_EXCEPTION` | FILE_UPLOAD | `pdf-po-exception-demo.json` | `/validation-review` | EXTRACTION + exception → READY_FOR_SCRIPTED_DEMO |
| `DISCOUNT_MARGIN_GUARDRAIL` | INTERNAL | — (scripted internal action) | `/pilot-readiness/evidence-report` | MARGIN_VIOLATION → READY_FOR_SCRIPTED_DEMO |
| `INVENTORY_MISMATCH` | INTERNAL | `reconciliation-demo.json` | `/reconciliation` | Reconciliation not yet linked to pilot evidence → stays PARTIAL |
| `BAD_AI_OUTPUT_REJECTED` | INTERNAL | `bad-ai-output-demo.json` | `/pilot-readiness` | Code-level safety guards → READY_FOR_SCRIPTED_DEMO (no seeded data needed) |

Each scenario entry carries: `code`, `title`, `actor`, `channel`, `inputSample`,
`expectedInterpretation`, `expectedValidationResult`, `expectedExceptionOrApproval`,
`safetyBoundary`, `demoRoute`, `evidenceImpact`, and `knownLimitation`.

The `pdf-po-exception-demo.json` fixture is a **plain-text stand-in** for a PDF (no binary PDF
is loaded) with one ambiguous SKU line (`CAMRY PADS`) and one unsupported UOM line (`BX`).

The `bad-ai-output-demo.json` fixture holds prompt-injection-like customer text and a malformed
model output. **All of it is data only** — it must never be executed, interpreted as a system
instruction, compiled, run as SQL, or used as a template. It is handled by
`PromptInjectionGuardService`, `ExtractionSchemaValidator`, and `AiOutputSanitizer`.

## How to load / use the demo data locally

The dataset is exercised via the existing test/local demo path — there is no production loader:

- **Tests / CI:** `DemoFixturesTest` and `PilotScriptedDemoFixtureTest` load and validate the
  fixtures. Pilot evidence and scenario readiness are covered by the existing pilot tests.
- **Local demo DB:** use the existing local seed script `scripts/seed-local-demo.ps1` (see
  `docs/runbooks/demo-seed-data.md` and `docs/runbooks/CORE_V1_LOCAL_DEMO_RUNBOOK.md`). It is
  Postgres/local-profile scoped and verifies the frozen demo rows.

Run the fixture tests:

```
cd apps/core-api
mvn -Dtest=PilotScriptedDemoFixtureTest test
mvn -Dtest=DemoFixturesTest test
```

## How to reset demo data safely

Resetting is local-only and handled by the existing local-demo tooling (drop/recreate the local
demo database via the documented local runbook). The seed path is **idempotent** — re-running it
upserts on the stable demo keys and does not duplicate rows. There is **no** automatic
destructive reset of existing data and **no** deletion of non-demo tenant data.

## What not to do in production

- Do **not** run any demo seed in a production profile or against production data.
- Do **not** expose a `/api/v1/demo/seed` (or similar) endpoint — none exists, by design.
- Do **not** put real customers, real Telegram bot configuration, real provider keys, or any
  secret into these fixtures. They are scanned by `PilotScriptedDemoFixtureTest`.
- The injection fixture is hostile sample text — never wire it into a real prompt, query, or
  template.

## Known limitations

- Telegram intake and PDF ingestion are local/dev and mock-extraction only; no production OCR/LLM
  and no production outbound messaging.
- The PDF PO source is plain text, not a binary PDF.
- Reconciliation outcomes are not yet composed into the pilot evidence report, so
  `INVENTORY_MISMATCH` stays honestly `PARTIAL`.
- Readiness never reaches 100%; `READY_FOR_SCRIPTED_DEMO` is the honest ceiling for this slice.

## Next steps toward a paid design-partner pilot

- Wire reconciliation discrepancies into pilot shadow evidence so `INVENTORY_MISMATCH` can be
  evidence-backed.
- Replace mock extraction with a gated, validated real-provider path under the safety model
  (AI suggests, rules validate, human approves, backend writes, audit records).
- Capture real (consented, anonymized) design-partner shadow runs to replace synthetic evidence.

This slice moves OrderPilot from "scenario readiness visible" to "scripted demo can be backed by
realistic fake data" — it does **not** claim paid-pilot readiness is complete.

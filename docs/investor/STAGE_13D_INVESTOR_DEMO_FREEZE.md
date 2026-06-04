# Stage 13D Investor Demo Freeze

Status note: This document is historical/superseded for current-stage tracking. The canonical current-stage source is `docs/product/current-stage.md`, which points to `docs/product/STAGE_STATUS_RECONCILIATION.md`. Do not use this file alone to determine the active implementation stage.

## Freeze Status

Stage 13D freezes the investor walkthrough as a repeatable, review-first demo package. This stage does not add product behavior, does not broaden bot behavior, and does not enable external execution.

## Frozen Demo Payload

The demo Telegram RFQ payload is frozen as:

```text
Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.
```

Do not change punctuation, quantity, unit, SKU, vehicle context, wholesale wording, or city before the investor walkthrough unless a verified blocker requires it.

## Frozen Seeded Defaults

Use these defaults on `/quotes`:

| Field | Frozen value |
| --- | --- |
| Customer external ref | `CUST-001` |
| Customer display | Steppe Logistics |
| Product/SKU | `PAD-OE-04465` |
| Requested item | Brake pads for Toyota Camry 2018 |
| Warehouse/location | `WH-ALM` |
| Quantity | `2` |
| Unit | `EA` |
| Substitute context | `PAD-SUB-ADV` and `PAD-SUB-ECON` when seeded data is present |

## Frozen Safety Posture

- Bot intake is allowed to classify and route the RFQ.
- Bot policy must require operator review for the RFQ path.
- Bot controls must not approve quotes, approve substitutes, create final orders, mutate master data, disclose uncontrolled customer-specific pricing, or execute external sends.
- Quote approval remains operator controlled.
- Substitute selection and approval remain operator controlled.
- Quote conversion remains internal-only.
- `externalExecution=DISABLED` remains visible on the demo path.
- ERP, 1C, external connector writes, and real Telegram outbound sends remain disabled.

## Screens Included In Walkthrough

1. `/demo`
2. `/bot-conversations`
3. `/quote-review`
4. `/quotes`

These screens are enough to show RFQ capture, policy routing, human review, quote validation, substitute context, audit evidence, approval controls, and the disabled external execution boundary.

## Screens Intentionally Excluded

- Connector execution or ERP/1C write screens.
- Production Telegram outbound sending.
- Autonomous approval or autonomous order creation screens.
- New product modules outside the rehearsed RFQ, quote review, quote approval, audit, and safety story.
- Broad analytics or reconciliation detours unless time remains after the core walkthrough.

## What Must Not Change Before Investor Walkthrough

- Do not change the frozen RFQ text.
- Do not change `/quotes` seeded defaults away from `CUST-001`, `PAD-OE-04465`, `WH-ALM`, `2 EA`.
- Do not enable external execution.
- Do not add real ERP, 1C, connector, warehouse, accounting, or Telegram outbound writes.
- Do not add autonomous quote approval.
- Do not add autonomous substitute approval.
- Do not broaden bot behavior or allowed flows for the demo.
- Do not hide operator review, approval requirements, audit metadata, or `externalExecution=DISABLED`.
- Do not replace the verification command set with a narrower one.

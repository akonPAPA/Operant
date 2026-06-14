# Operant Order Journey & Fulfillment Visibility (OP-CAP-22)

A safe, tenant-scoped order journey layer that shows the operational lifecycle of quotes / orders /
fulfillment signals. It is a **visibility foundation**, not a WMS/TMS/carrier/GPS/payment platform.

## Lifecycle model

```
Commercial request -> extraction/validation -> draft quote -> quote approved/sent ->
draft order -> order confirmed -> payment (if real signal) -> fulfillment preparing ->
packed -> ready to ship -> shipped -> delivered -> closed / cancelled / blocked-exception
```

Canonical milestone codes (`MilestoneCode`): REQUEST_RECEIVED, VALIDATION_STARTED,
VALIDATION_COMPLETED, QUOTE_DRAFTED, QUOTE_SENT, QUOTE_APPROVED, ORDER_DRAFTED, ORDER_CONFIRMED,
PAYMENT_PENDING, PAYMENT_CONFIRMED, FULFILLMENT_PREPARING, PACKED, READY_TO_SHIP, SHIPPED,
DELIVERED, CLOSED, CANCELLED, BLOCKED_EXCEPTION.

## Status authority

`VERIFIED > MIRRORED > SYSTEM_DERIVED > ESTIMATED > MANUAL (operator-attested) > UNKNOWN`.

- **Verified** — internal trusted signal (e.g. INTERNAL fulfillment signal).
- **Mirrored** — imported / connector-mirrored external signal; never treated as fully verified.
- **System-derived** — deterministic internal workflow status (draft/validation/reconciliation state).
- **Estimated** — projected, not yet evidenced.
- **Unknown** — no evidence available; the surface says so honestly.

The frontend and AI never invent journey statuses. Unverified external payloads never become
authoritative. Journeys are derived projections; the source object (draft quote/order, validation
review case, reconciliation case) remains the system of record.

## Customer-visible vs internal-only

Every milestone/event carries a `customerVisible` flag. The journey stores both
`customerVisibleStatus` and `internalStatus`. The `CustomerSafeJourneyDto` exposes only
customer-visible status, customer-visible milestones, and customer-visible events — internal status,
risk level, internal-only steps (validation/drafting), and signal internals are excluded. The
internal block reason is never revealed to the customer-visible status.

## APIs

| Method | Path | Permission | Notes |
| --- | --- | --- | --- |
| GET | `/api/v1/order-journeys` | ANALYTICS_READ | bounded list (limit clamped to 50) |
| GET | `/api/v1/order-journeys/attention` | ANALYTICS_READ | blocked or HIGH-risk journeys, bounded |
| GET | `/api/v1/order-journeys/{id}` | ANALYTICS_READ | detail: milestones (sorted), recent events, signals |
| GET | `/api/v1/order-journeys/by-source` | ANALYTICS_READ | idempotently materializes + returns the journey for a source |
| POST | `/api/v1/order-journeys/{id}/signals` | REVIEW_ACTION | audited internal fulfillment signal ingest |

All reads are tenant-scoped (`X-Tenant-Id` / `TenantContext`) and bounded (count queries +
`findTop…`/`Page(0,N)`). The POST is an audited operator action (`RequestActorResolver` actor,
`AuditEventService` record) and performs **no external/connector/ERP/payment write**.

## DTOs

`OrderJourneyDtos`: `OrderJourneySummaryDto`, `OrderJourneyListItemDto`, `OrderJourneyDetailDto`,
`OrderJourneyMilestoneDto`, `OrderJourneyEventDto`, `FulfillmentSignalDto`,
`OrderJourneyAttentionSummaryDto`, `CustomerSafeJourneyDto`, `RecordFulfillmentSignalRequest`. They
exclude raw payloads, secrets, raw AI prompts, raw document contents, and payment-sensitive data.

## Security model

- Tenant isolation on every query and mutation.
- No external integrations; no connector/ERP/1C writes; no PSP/bank/card/NFC data.
- Fulfillment signals store no raw carrier/GPS/payment payload — only a `rawPayloadRef` object
  reference and bounded fields.
- Payment milestones are always `UNKNOWN` (no payment mirror domain wired). A fulfillment signal has
  no payment signal type, so it is structurally incapable of asserting payment confirmation.
- AI is never an authoritative actor; the frontend never mutates business data.

## Frontend

- `lib/order-journey-api.ts` — read-only client (`X-Tenant-Id` + `X-OrderPilot-Permissions:
  ANALYTICS_READ`); returns `{ data: null, error }` on failure.
- Components: `order-journey-list`, `order-journey-detail`, `order-journey-timeline`,
  `order-journey-status-badge`, `fulfillment-signal-panel`.
- Pages: `app/(dashboard)/order-journey/page.tsx` (list) + `order-journey/[id]/page.tsx` (detail),
  inside the Operant dark shell; nav entry under **Transactions**.
- Honest empty states ("No order journey signals yet.", "Fulfillment tracking not connected yet.",
  "Payment status unavailable."). No fake map/GPS/carrier/payment data.

## Why no fake GPS / payment / carrier data

A visibility layer that fabricates carrier or payment truth would undermine the safety model (AI
suggests, rules validate, backend writes, audit records). We surface only evidence we actually hold
and label its evidence level; everything else is honestly `UNKNOWN` / "not connected yet".

## Future integration

- Payment milestones backed by a real payment/reconciliation mirror (still read-only, mirrored
  evidence).
- Connector-mirrored fulfillment signals from ERP/1C/WMS/TMS via the existing connector abstraction
  (read-only, MIRRORED evidence, no external writes).
- Secure customer-facing tracking surface once authorized secure-link backend exists
  (`CustomerSafeJourneyDto` is the contract).

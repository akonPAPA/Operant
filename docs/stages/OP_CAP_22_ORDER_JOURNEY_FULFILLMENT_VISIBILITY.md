# OP-CAP-22 — Order Journey & Fulfillment Visibility Foundation

## Objective

Extend Operant from a request/validation/quote/order cockpit into commercial transaction visibility:
a safe, tenant-scoped, read-first order journey layer showing where an order sits in the lifecycle,
which milestones are verified vs estimated, what is blocked, what is customer-safe, and what evidence
produced the current status. Foundation stage — production-shaped but bounded. Not a WMS/TMS/carrier/
GPS/payment platform.

## What shipped

### Backend
- Domain (`com.orderpilot.domain.journey`): entities `OrderJourney`, `OrderJourneyMilestone`,
  `OrderJourneyEvent`, `FulfillmentSignal`; enums `JourneySourceType`, `MilestoneCode` (18 canonical),
  `MilestoneState`, `EvidenceLevel`, `FulfillmentSignalSource`, `FulfillmentSignalType`,
  `JourneyActorType`.
- Repositories (bounded only): `OrderJourneyRepository` (by-source, recent `Pageable`, attention
  `@Query`, counts), `OrderJourneyMilestoneRepository` (sorted, delete-by-journey),
  `OrderJourneyEventRepository` (recent `Pageable`), `FulfillmentSignalRepository` (recent/asc).
- Services: `OrderJourneyService` (derive/build/refresh + audited signal ingest, no external writes),
  `OrderJourneyReadService` (list/attention/detail/by-entity/customer-safe, all bounded).
- DTOs: `OrderJourneyDtos`.
- Controller: `OrderJourneyController` — `GET /api/v1/order-journeys`, `/attention`, `/{id}`,
  `/by-source`; audited `POST /{id}/signals`.
- Permissions: `ApiPermissionInterceptor` — `/api/v1/order-journeys` GET→ANALYTICS_READ,
  non-GET→REVIEW_ACTION.
- Command Center: one bounded `blockedJourneys` count metric added to OP-CAP-21 summary (no DTO
  reshape, single count query).

### Migration
- `V53__order_journey_fulfillment_visibility.sql` — `order_journey`, `order_journey_milestone`,
  `order_journey_event`, `fulfillment_signal`. tenant_id everywhere; unique `(tenant_id, source_type,
  source_id)` and `(tenant_id, journey_id, milestone_code)`; indexes for stage/status, blocked,
  customer, journey+sort, journey+occurred, signal+received. No JSONB hot keys; `raw_payload_ref`
  reference only (no raw payload stored).

### Frontend
- `lib/order-journey-api.ts`; components `order-journey-list`, `order-journey-detail`,
  `order-journey-timeline`, `order-journey-status-badge`, `fulfillment-signal-panel`; pages
  `order-journey/page.tsx` + `order-journey/[id]/page.tsx`; nav entry under **Transactions**.

## Status authority

`VERIFIED > MIRRORED > SYSTEM_DERIVED > ESTIMATED > MANUAL > UNKNOWN`. Payment milestones are always
UNKNOWN (no payment mirror wired); a fulfillment signal cannot assert payment (no payment signal
type). Frontend/AI never invent statuses.

## Derivation summary

REQUEST_RECEIVED always completed; validation milestones from validationRunId/exceptionCase; QUOTE_/
ORDER_DRAFTED from source kind; QUOTE_APPROVED/ORDER_CONFIRMED/CANCELLED from status; reconciliation
open → BLOCKED_EXCEPTION + blocked; fulfillment milestones from ingested signals with the signal
source's evidence level; payment UNKNOWN.

## Tests

- Backend `OrderJourneyServiceTest` (6): draft-quote ordered-milestone derivation + payment stays
  UNKNOWN; tenant isolation; signal advances milestone + is audited + cannot fake payment;
  reconciliation journey blocked + appears in attention; customer-safe view excludes internal-only;
  list bounded by limit cap. `ApiPermissionInterceptorPermissionTest` (+4 → 127). OP-CAP-21
  `CommandCenterReadServiceTest` (5) still green with the new dependency/metric.
- Frontend `tests/order-journey.test.mjs` (9): read-only client, nav under Transactions, list
  columns + empty state, badges distinguish verified/mirrored/estimated/unknown/blocked, timeline
  state+evidence+customer-vs-internal, detail status separation + honest payment, no fake
  GPS/carrier/tracking/payment copy, no mutation controls, Operant branding preserved.

## Security / performance notes

- Tenant-scoped everywhere; bounded count/Top/Page reads — no full scans, no unbounded joins.
- No external/connector/ERP/payment writes; no PSP/bank/card/NFC/raw payloads; no AI decisions.
- Signal ingest audited via `AuditEventService`; actor from `RequestActorResolver` (never body).

## Known limitations / non-scope

- No WMS/TMS/route optimization/live GPS/carrier API; no payment provider; no customer-facing public
  tracking link (the `CustomerSafeJourneyDto` is the future contract).
- `by-source` materializes the projection on read (idempotent, audited) — acceptable for a derived
  read model; a scheduled/event-driven projector is a future option.
- ORDER and EXTERNAL_MIRROR sources are modeled but minimally derived (no dedicated order domain yet).

## Next recommended stage

Event/outbox-driven journey projector (replace on-read materialization), payment-mirror-backed
payment milestones (read-only, MIRRORED), and an authorized customer-safe tracking surface.

## Branding confirmation

No backend technical rename was performed. `com.orderpilot`, `/api/v1`, `X-OrderPilot-*` headers,
table names, and package paths are unchanged. "Operant" is used only in visible frontend branding.

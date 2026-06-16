# OP-CAP-24 — Wire Journey Projection Events into Source Mutation Points

## Objective

Make the OP-CAP-23 Order Journey projector useful in normal workflows: real backend business mutations now
publish durable, idempotent `OrderJourneyProjectionEvent`s **inside the same transaction**, so the explicit
projector runner turns them into READY journeys — instead of the journey depending primarily on the
`by-source` on-read fallback or manual projection requests. Journey state still comes only from backend
business events; AI never authors it and the frontend never invents it.

## Architecture (per hooked mutation)

```
business mutation service (existing @Transactional)
  -> save source entity            (unchanged business behaviour)
  -> audit event                   (existing convention, unchanged)
  -> publish OrderJourneyProjectionEvent  (in the SAME transaction; idempotent; no projector run here)
  -> commit
... later, explicitly ...
  -> OrderJourneyProjectorRunner.processTenantBatch(limit)
  -> OrderJourney projection updated
  -> frontend reads the READY projection
```

The hook **only publishes a durable event**. It never runs the projector, never mutates the journey
directly, and never performs an external/AI write. A publish failure follows the caller's existing
transaction/error convention (outbox-in-same-tx): the business mutation and the event commit or roll back
together — no silent loss of a critical event.

## Hooks implemented

| Source mutation | Service / method | Event type |
| --- | --- | --- |
| Draft quote created | `DraftQuoteService.createFromValidation(...)` (all overloads + `DraftCommandPreparationService` delegate here) | `DRAFT_QUOTE_CREATED` |
| Draft order created | `DraftOrderService.createFromValidation(...)` | `DRAFT_ORDER_CREATED` |
| Validation review registered | `ExceptionCaseService.createFromValidation(...)` (canonical; `ValidationReviewService.createForExtractionResult` delegates, idempotent reuse) | `VALIDATION_REVIEW_REGISTERED` |
| Reconciliation case created/updated | `InventoryReconciliationService.runInventoryReconciliation(...)` (guarded by `created \|\| materiallyChanged`) and `updateCaseStatus(...)` | `RECONCILIATION_CASE_CREATED` / `RECONCILIATION_CASE_UPDATED` |
| Fulfillment signal recorded | `OrderJourneyService.recordSignal(...)` (keeps the existing synchronous OP-CAP-22 refresh) | `FULFILLMENT_SIGNAL_RECORDED` |

## Idempotency key format

A new `OrderJourneyProjectionPublisher.publishSourceEvent(...)` / static `sourceEventIdempotencyKey(...)`:

```
journey:{tenantId}:{eventType}:{sourceType}:{sourceId}[:{discriminator}]
```

- Always includes tenant id, source type, source id. Never includes raw payload or secrets.
- **Create-once sources** (draft quote/order, validation review): no discriminator — the fresh source id
  makes the key unique; duplicate triggers/retries collapse to one event.
- **Repeatable transitions**: a discriminator distinguishes meaningful updates — reconciliation uses this
  run's instant (material change → new event) / the new status; fulfillment uses the new signal id (so
  distinct signals get distinct events, but reprocessing one signal stays idempotent).

Publishing reuses the OP-CAP-23 `OrderJourneyProjectionPublisher.publish(...)`, which is idempotent per
`(tenant, idempotency_key)`.

## Reason codes / event types

Existing `JourneyProjectionEventType` values are reused; `RECONCILIATION_CASE_CREATED` was added alongside
the existing `RECONCILIATION_CASE_UPDATED`. The projector resolves source generically by
`sourceType`/`sourceId`, so new reason codes need no projector change.

## Hooks deferred (and why)

- **`QuoteDraftService` (Stage12A) and `RfqToDraftQuoteService` (Stage11A)** alternate quote builders —
  not the validation→draft journey path; hooking them would broaden surface for little journey value. The
  `by-source` fallback still covers any draft they create.
- **Validation *completion* / issue-resolution transitions** — spread across several services with no
  single clean state change that maps to a journey transition; forcing it risks a broad refactor.
- **Reconciliation `createStaleInventoryWarnings` bulk warning creation** — an internal bulk path; left out
  to avoid touching the Stage8 bulk loop further. Per-case `runInventoryReconciliation` already covers the
  primary create/update path.

## by-source fallback status

Unchanged from OP-CAP-23 and **retained**: a `by-source` read returns `projectionSource=READY` when a
projection exists (now the common case for hooked sources), otherwise publishes a refresh request and
returns `ON_READ_FALLBACK`. It remains the backward-compatible safety net for not-yet-hooked sources and
for journeys whose projector batch has not yet run. Retirement path: once hooks + a scheduled/explicit
projector pass guarantee a projection exists before first read, the on-read materialization can be removed.

## Why the frontend does not connect directly to AI

The frontend reads only backend-owned, bounded read models over the Core API (`X-Tenant-Id` +
`X-OrderPilot-Permissions`). It never calls AI models/workers, databases, ERP/1C, payment providers or
carriers. AI is **advisory only** — it may extract/classify/summarize/rank/suggest through
backend-controlled services, but it never authors authoritative journey status and never tells the
frontend that an order is paid/shipped/delivered. Journey status comes exclusively from deterministic
backend business events (the hooks above) projected into the OrderJourney read model. This preserves the
safety model: AI suggests, rules validate, humans approve if risky, the backend writes, audit records.

## Security notes

- Tenant isolation: every hook publishes with the source's own `tenantId`; the projector loads events only
  by `(id, tenant)`; a tenant-A event can never create/update a tenant-B journey (tested).
- Permissions/audit preserved: no endpoint contracts changed; existing audit events at each mutation are
  untouched; the explicit projection-request endpoint still audits `ORDER_JOURNEY_PROJECTION_REQUESTED`.
- No external/ERP/1C/PSP/carrier/AI writes; no raw payloads/secrets in events; bounded columns.
- No fake paid/shipped/delivered state; payment milestones remain `UNKNOWN` (tested).

## Performance notes

- Each hook adds one idempotent publish (1 keyed select + at most 1 insert) inside an already-transactional
  mutation — bounded and proportional. In the Stage8 bulk `refreshProjections` loop, at most one event per
  *changed* reconciliation case is published (no event for unchanged cases).
- The projector remains explicit/no-daemon; batches are clamped (default 50, max 200); all queries are
  tenant-scoped and indexed.

## Tests

Backend: `OrderJourneySourceHookStage24Test` (7) — draft quote/order create publish exactly one event and
project to READY; duplicate trigger for the same source is idempotent; validation review registration
publishes an event; reconciliation create publishes an event and projects a blocked journey with payment
`UNKNOWN`; fulfillment signal publishes an event, keeps one signal row, fabricates no payment milestone;
tenant-A hook creates no tenant-B journey. Regression: `OrderJourneyProjectionServiceTest` (10),
`OrderJourneyServiceTest` (6), `InventoryReconciliationServiceTest` (7), `DraftPreparationFoundationStage9ATest`
(8), plus a broader 227-test sweep — all green. Frontend: `order-journey` test green; full suite 275/275;
lint/tsc clean; build OK.

## Non-goals

Direct AI↔frontend integration; AI-authored journey state; PSP/bank payment milestones or real payment
integration; customer public tracking links; carrier/TMS/WMS; GPS/maps/routes; connector/ERP/1C writes;
Kafka/Redpanda/new infra; broad validation/reconciliation refactor; global technical rename.

## Next recommended stage

OP-CAP-25 — a scheduled/triggered projector pass (using the existing ProcessingJob runtime if it cleanly
supports it) so pending source-hook events are drained without an explicit operator call, enabling
retirement of the `by-source` on-read fallback.

## Confirmations

- No direct AI-to-frontend integration was added.
- No backend technical rename was done (`com.orderpilot`, `/api/v1`, `X-OrderPilot-Permissions`, table
  names unchanged).

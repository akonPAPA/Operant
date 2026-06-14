# Order Journey Projector Runtime (OP-CAP-23)

A controlled, idempotent, tenant-scoped runtime that refreshes Order Journey projections from durable
internal events. It mirrors the OP-CAP-18 Trust/AI Event Projector Runtime. **There is no background
daemon, scheduler, or external queue** — processing is driven by an explicit, tenant-scoped call/endpoint.

## Components

| Concern | Type |
| --- | --- |
| Durable event | `OrderJourneyProjectionEvent` (`order_journey_projection_event`) |
| Idempotency guard | `OrderJourneyProjectionCheckpoint` (`order_journey_projection_checkpoint`) |
| Publisher | `OrderJourneyProjectionPublisher` (idempotent per `(tenant, idempotency_key)`) |
| Projector | `OrderJourneyProjector` (resolve source → refresh) |
| Runtime | `OrderJourneyProjectorRunner` (`processTenantBatch`, `processEvent` REQUIRES_NEW, `requestProjection`, `health`) |

## Event types

`DRAFT_QUOTE_CREATED`, `DRAFT_ORDER_CREATED`, `VALIDATION_REVIEW_REGISTERED`,
`RECONCILIATION_CASE_UPDATED`, `FULFILLMENT_SIGNAL_RECORDED`, `ORDER_JOURNEY_REFRESH_REQUESTED`. Each
carries its own `sourceType`/`sourceId` (a trusted internal row). Payloads are bounded and sanitized:
no raw document/OCR/prompt/message text, no payment/bank/card data, no secrets.

## Lifecycle

`PENDING → PROCESSING → {PROCESSED | SKIPPED | FAILED}`; `FAILED` retries with a fixed +5m backoff up to
3 attempts then `DEAD_LETTERED`. A COMPLETED/SKIPPED checkpoint makes re-processing a no-op.

## Outcomes

- **PROJECTED** — source resolved, journey rebuilt idempotently (checkpoint COMPLETED).
- **SKIPPED `UNSUPPORTED_SOURCE`** — non-projectable source type (`EXTERNAL_MIRROR`, out of scope).
- **SKIPPED `SOURCE_NOT_FOUND`** — no source row for `(tenant, type, id)`.
- **FAILED `INVALID_PAYLOAD`** — e.g. missing `sourceId`; bounded reason; retried then dead-lettered.

## Endpoints (under `/api/v1/order-journeys`)

- `GET  /projection-health` (ANALYTICS_READ) — pending/failed/dead-lettered counts, failed checkpoints,
  last processed, recent bounded failures.
- `POST /projection/process?limit=` (REVIEW_ACTION) — run a clamped tenant batch.
- `POST /projection-requests` (REVIEW_ACTION) — audited, idempotent refresh request for a known source.

## Source-mutation hooks (OP-CAP-24)

Real backend business mutations publish a durable, idempotent event **in the same transaction** (no
projector run, no journey mutation, no external/AI write at the hook):

| Mutation | Service | Event type |
| --- | --- | --- |
| Draft quote created | `DraftQuoteService.createFromValidation` | `DRAFT_QUOTE_CREATED` |
| Draft order created | `DraftOrderService.createFromValidation` | `DRAFT_ORDER_CREATED` |
| Validation review registered | `ExceptionCaseService.createFromValidation` | `VALIDATION_REVIEW_REGISTERED` |
| Reconciliation case created/updated | `InventoryReconciliationService.runInventoryReconciliation` (guarded) / `updateCaseStatus` | `RECONCILIATION_CASE_CREATED` / `RECONCILIATION_CASE_UPDATED` |
| Fulfillment signal recorded | `OrderJourneyService.recordSignal` (keeps OP-CAP-22 synchronous refresh) | `FULFILLMENT_SIGNAL_RECORDED` |

Hook idempotency key (`OrderJourneyProjectionPublisher.publishSourceEvent` /
`sourceEventIdempotencyKey`): `journey:{tenantId}:{eventType}:{sourceType}:{sourceId}[:{discriminator}]`.
Discriminator distinguishes repeated transitions (reconciliation: run instant / new status; fulfillment:
new signal id); create-once sources use no discriminator. No raw payload/secret in the key.

Deferred hooks: alternate quote builders (`QuoteDraftService`, `RfqToDraftQuoteService`), validation
completion/issue-resolution, and reconciliation stale-warning bulk creation — covered by the `by-source`
fallback for now.

## Operating notes

- Drive `POST /projection/process` from an operator action or an internal cron caller; the limit is
  clamped (default 50, max 200).
- `by-source` reads prefer an already-projected journey (`projectionSource=READY`) and only materialize
  on read as a labelled, temporary fallback (`ON_READ_FALLBACK`) while emitting a durable refresh request.
- Everything is tenant-isolated; event ids are never trusted across tenants.

# OP-CAP-23 — Event/Outbox-driven Order Journey Projector

## Objective

Move the OP-CAP-22 Order Journey layer from **materialized-during-read** toward a production-shaped
**projection** model: relevant business events (or explicit projection requests) update the journey
projection asynchronously and idempotently through a durable event + projector + checkpoint runtime, so
the read endpoints serve already-prepared projections instead of causing write-like materialization on
read. This makes the journey layer more reliable, observable, idempotent, and scalable without adding
any new infrastructure.

This stage deliberately mirrors the existing **OP-CAP-18 Trust/AI Event Projector Runtime** pattern
(`trust_ai_domain_event` + `trust_ai_projection_checkpoint` + publisher + projector + explicit,
no-daemon runtime). No parallel event bus or new infra was invented.

## Why on-read materialization is being replaced

In OP-CAP-22, `GET /api/v1/order-journeys/by-source` called `OrderJourneyService.ensureJourney(...)`,
which **writes** (builds/refreshes the projection) during a read. That is acceptable for a foundation
but is not the final runtime shape: reads should not own projection creation/refresh. OP-CAP-23 makes
the durable projector the owner of projection refresh and keeps on-read materialization only as an
explicitly-labelled, temporary fallback.

## Architecture

```
business mutation / explicit request
  -> (in-tx) durable OrderJourneyProjectionEvent   (bounded, sanitized, tenant-scoped, idempotent)
  -> OrderJourneyProjectorRunner.processTenantBatch / processEvent   (explicit call, no daemon)
  -> OrderJourneyProjector.project(event)            (resolve source by tenant+type+id)
  -> OrderJourneyService.refreshIfSourcePresent      (rebuild, not append; idempotent)
  -> OrderJourney projection update
  -> OrderJourneyProjectionCheckpoint recorded       (per (tenant, projector, event) idempotency guard)
  -> frontend reads bounded GET APIs
```

## What shipped

### Migration
- `V54__order_journey_projection_runtime.sql` (next after V53; **V53 not modified**). Two tenant-scoped,
  bounded-VARCHAR tables: `order_journey_projection_event` (unique `(tenant_id, idempotency_key)`) and
  `order_journey_projection_checkpoint` (unique `(tenant, projector, event)` and
  `(tenant, projector, idempotency_key)`), with tenant/status/type indexes. No raw payload columns.

### Domain (`com.orderpilot.domain.journey.events`)
- Enums: `JourneyProjectionEventType` (`DRAFT_QUOTE_CREATED`, `DRAFT_ORDER_CREATED`,
  `VALIDATION_REVIEW_REGISTERED`, `RECONCILIATION_CASE_UPDATED`, `FULFILLMENT_SIGNAL_RECORDED`,
  `ORDER_JOURNEY_REFRESH_REQUESTED`), `JourneyProjectionEventStatus`,
  `JourneyProjectionCheckpointStatus`.
- Entities: `OrderJourneyProjectionEvent` (carries `tenantId, sourceType, sourceId, reasonCode,
  correlationId, causationId, requestedAt(occurredAt)`, bounded `payloadSummary` ≤ 512, status lifecycle),
  `OrderJourneyProjectionCheckpoint` (projected-record ref + bounded failure/skip reason).
- Repositories: tenant-scoped, bounded finders + `findPendingBatch` (PENDING + retry-ready FAILED under
  cap, oldest-first) + health counts.
- Additive helper `JourneySourceType.isProjectable()` (`EXTERNAL_MIRROR` is not projectable this stage).

### Application (`com.orderpilot.application.services.journey`)
- `OrderJourneyProjectionPublisher` — idempotent publish per `(tenant, idempotencyKey)`;
  `publishRefreshRequest(...)` convenience + stable `refreshIdempotencyKey(...)`; bounded payloads.
- `OrderJourneyProjector` — resolves source and refreshes idempotently; classifies outcomes (PROJECTED /
  SKIPPED with reason / throws → FAILED).
- `OrderJourneyProjectorRunner` — `processTenantBatch(limit)`, `processEvent(REQUIRES_NEW)`,
  `requestProjection(...)` (audited), `health(...)`. Clamped batches, per-event transaction, retry →
  dead-letter, no background thread.
- `OrderJourneyService.refreshIfSourcePresent(...)` — projector-safe refresh that returns empty (instead
  of throwing) for a missing source, avoiding a rollback-only trap.

### API (under existing `/api/v1/order-journeys` prefix — no interceptor change)
- `GET  /api/v1/order-journeys/projection-health` (ANALYTICS_READ) — bounded counts + recent failures.
- `POST /api/v1/order-journeys/projection/process?limit=` (REVIEW_ACTION) — explicit tenant batch run.
- `POST /api/v1/order-journeys/projection-requests` (REVIEW_ACTION) — audited, idempotent refresh request.
- `GET  /api/v1/order-journeys/by-source` — **changed**: prefers an already-projected journey
  (`projectionSource=READY`); when none exists it publishes a durable refresh request **and** returns a
  read-materialized projection tagged `projectionSource=ON_READ_FALLBACK` (documented temporary fallback).
  `OrderJourneyDetailDto` gained one trailing optional field `projectionSource`.

### Frontend (minimal)
- `order-journey-api.ts`: optional `projectionSource` on the detail type; `JourneyProjectionHealth` type +
  read-only `getJourneyProjectionHealth()`.
- `order-journey-detail.tsx`: honest "Projection" row ("Prepared by projector" / "Refreshed on read
  (projector pending)"). No fake projector controls, no fake GPS/carrier/payment copy; Operant shell/nav
  preserved.

## Idempotency model

- **Publish** is idempotent per `(tenant, idempotency_key)` — duplicate triggers collapse to one event.
- **Processing** is guarded by a per-`(tenant, projector, event)` checkpoint: a COMPLETED/SKIPPED
  checkpoint makes re-processing a no-op; the unique constraint prevents double-processing under races.
- **Refresh** rebuilds the projection's milestones (delete-then-insert inside the service), so the same
  event processed twice yields no duplicate journeys, milestones, events, or signals.
- A duplicate `FULFILLMENT_SIGNAL_RECORDED`-typed refresh does not duplicate customer-visible timeline
  (it just re-derives from source + persisted signals).

## Checkpoint model

`order_journey_projection_checkpoint` records, per event and projector: status (STARTED/COMPLETED/
SKIPPED/FAILED), projected record ref (`ORDER_JOURNEY` + journey id), bounded failure/skip code+message,
attempt count, timestamps. No raw payload is stored.

## Failure handling

- Invalid payload (e.g. null `sourceId`) → **FAILED** with bounded `INVALID_PAYLOAD` reason; retried with
  fixed +5m backoff up to the cap, then **DEAD_LETTERED** (no infinite loop).
- Non-projectable source type (`EXTERNAL_MIRROR`, out of scope) → **SKIPPED** (`UNSUPPORTED_SOURCE`).
- Source row not found for `(tenant, type, id)` → **SKIPPED** (`SOURCE_NOT_FOUND`).
- Cross-tenant event id under another tenant → `NotFoundException` (never processed).

## Security constraints

- Tenant isolation on every lookup; events/checkpoints/journeys are all tenant-scoped; cross-tenant
  processing is impossible.
- Permissions preserved: reads ANALYTICS_READ, writes/processing REVIEW_ACTION (inherited via prefix).
- Audit preserved: explicit projection requests emit `ORDER_JOURNEY_PROJECTION_REQUESTED`; each refresh
  still emits `ORDER_JOURNEY_REFRESHED` (via OP-CAP-22 service).
- No raw documents/OCR/prompts/messages, payment/bank/card data, secrets, or credentials in payloads or
  DTOs; all text columns bounded.
- No external/ERP/1C/connector/PSP/carrier writes; no AI calls; AI never authors journey status.

## Performance notes

- All queries are bounded (clamped `Pageable`, count-only health metrics); `findPendingBatch` is
  tenant + status indexed and oldest-first. No unbounded scans, no N+1 added.
- Processing is per-event `REQUIRES_NEW`, so one failure never rolls back the batch.

## Non-goals (unchanged from OP-CAP-22, reaffirmed)

Real payment provider/PSP/bank integration; payment milestones from PSP; customer public tracking links;
carrier/TMS/WMS; GPS/maps/routes; AI-generated journey status; connector/ERP/1C writes; Kafka/Redpanda/
new microservices/major infra; global technical rename to Operant.

## Known limitations / future path

- Source-transition hooks (auto-publishing on draft/validation/reconciliation create) are **not** wired
  this stage to avoid broad refactors of those flows; the explicit `projection-requests` API + the
  `by-source` request emission provide the event path now. Wiring in-transaction outbox writes at those
  mutation points is the next step.
- `by-source` still keeps a safe on-read fallback for backward compatibility (clearly labelled
  `ON_READ_FALLBACK`); it can be retired once hooks guarantee projections exist before first read.
- Command Center was intentionally **not** reshaped (avoids `CommandCenterSummaryDto` + frontend +
  test churn); projector health is served by the dedicated bounded `projection-health` endpoint instead.
- Future: payment-mirror milestones (read-only, MIRRORED evidence) and a secure customer tracking surface.

## Next recommended stage

OP-CAP-24 — wire in-transaction outbox publication at draft-quote/draft-order/validation-review/
reconciliation mutation points so projections are produced by real business events, then retire the
`by-source` on-read fallback.

## Verification

- Backend: `OrderJourneyProjectionServiceTest` (10) + `OrderJourneyServiceTest` (6) +
  `ApiPermissionInterceptorPermissionTest` (133, +6 OP-CAP-23) + `CommandCenterReadServiceTest` (5) —
  154 run, 0 failures, BUILD SUCCESS.
- Frontend: `npm run lint` clean; `npx tsc --noEmit` clean; `node --test tests/*.test.mjs` 275/275;
  `npm run build` succeeds.
- No backend technical rename performed (`com.orderpilot`, `/api/v1`, `X-OrderPilot-Permissions`, table
  names unchanged).

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

## Controlled drain runtime (OP-CAP-25)

OP-CAP-23/24 left projector processing **explicit/manual** — pending events did not drain unless someone
called `POST /projection/process` (or the runner). OP-CAP-25 adds a **controlled, bounded, tenant-safe
drain** so journeys update automatically after backend source events, **without a `while(true)` daemon,
unmanaged threads, or any new queue/infrastructure**. It does **not** re-implement projection: it discovers
which tenants have drainable work and delegates to the existing `OrderJourneyProjectorRunner`.

| Concern | Type |
| --- | --- |
| Drain service | `OrderJourneyProjectionDrainService` (`drainTenant`, `drainTenants`, `drainOnce`, `pendingTenantCount`) |
| Cross-tenant discovery | `OrderJourneyProjectionEventRepository.findTenantIdsWithPendingEvents(maxRetry, now, Pageable)` — distinct tenant ids, oldest-first, retry-aware, **never** `DEAD_LETTERED`, tenant ids only (no payload) |
| Config-gated scheduler | `OrderJourneyProjectionSchedulingConfiguration` (`@EnableScheduling` + `@ConditionalOnProperty`), `OrderJourneyProjectionScheduledDrain` (`@Scheduled(fixedDelayString=…)`) |
| Drain endpoint | `POST /api/v1/order-journeys/projection/drain?perTenantLimit=` (REVIEW_ACTION) — **current tenant only** |

### Tenant context

`OrderJourneyService.refreshFromSource` resolves the trusted source row via the **ambient** `TenantContext`.
The drain therefore binds each tenant's `TenantContext` for the duration of that tenant's batch and restores
the previous context in `finally`, so the system/scheduled drain (which has no inbound request tenant) works
and never leaks one tenant's context into another's work.

### Bounds & safety

- **Per-tenant batch** clamped by `OrderJourneyProjectionPublisher.clampBatch` (default 25, max 200).
- **Tenants per cycle** clamped (`max-tenants-per-cycle`, hard ceiling 50); discovery is always `Pageable`.
- **Fairness**: tenants ordered by oldest drainable event (`min(occurredAt)`) — no tenant/event starvation.
- **Liveness**: a per-tenant failure is caught (error class only, no tenant id/payload logged) so one
  unhealthy tenant never blocks the rest of the cycle.
- **Idempotency / retry / dead-letter**: unchanged — owned by the runner's per-event `REQUIRES_NEW`
  checkpoint, fixed +5m backoff, 3 attempts then `DEAD_LETTERED`. Dead-lettered events are excluded by
  discovery and never retried.
- **Response**: `OrderJourneyProjectionDrainSummary` — counts/flags only (`tenantsScanned`, `eventsProcessed/
  Skipped/Failed/DeadLettered`, `partial`, `limitApplied`, `generatedAt`). No tenant names/customer data.

### Configuration (`orderpilot.runtime.order-journey-projection`, disabled by default)

| Property | Default | Notes |
| --- | --- | --- |
| `enabled` | `false` | When `false`, **no** scheduling infra and **no** scheduled bean exist — explicit/manual only. |
| `batch-size` | `25` | Per-tenant event batch; clamped to ≤200 in code even if mis-set. |
| `max-tenants-per-cycle` | `10` | Tenants per scheduled cycle; clamped to ≤50 in code. |
| `fixed-delay-ms` | `30000` | Scheduled drain fixed delay (and initial delay). |

Tests never enable it, so the safe no-daemon default holds; an enabled context starts cleanly and registers
exactly one scheduled drain bean (`OrderJourneyProjectionScheduledDrainConfigTest`).

### `ON_READ_FALLBACK` retirement plan

`by-source` still returns `ON_READ_FALLBACK` (materialize-on-read) when no projection exists yet — **retained
for compatibility, documented as temporary**. It can be retired only when: (1) all major source create/update
paths publish events (see deferred hooks above); (2) the controlled/scheduled drain is enabled in the target
environment; (3) missed-event recovery exists; (4) production monitoring of `projection-health` is in place;
and (5) measured fallback usage is near zero. Health now also reports `oldestPendingAt`, `schedulerEnabled`,
and `configuredBatchSize` to support that monitoring.

## Operating notes

- Drive `POST /projection/process` from an operator action or an internal caller; the limit is clamped
  (default 50, max 200). The OP-CAP-25 `POST /projection/drain` is the bounded, current-tenant equivalent.
- Enable the scheduled drain (`enabled=true`) for automatic multi-tenant draining; it is config-gated and
  bounded, with no daemon/queue. The cross-tenant path is reserved for the scheduler — no endpoint drains
  all tenants.
- `by-source` reads prefer an already-projected journey (`projectionSource=READY`) and only materialize
  on read as a labelled, temporary fallback (`ON_READ_FALLBACK`) while emitting a durable refresh request.
- Everything is tenant-isolated; event ids are never trusted across tenants. The frontend never connects to
  the projector/runtime, the database, or AI — it reads backend-owned projection state only.

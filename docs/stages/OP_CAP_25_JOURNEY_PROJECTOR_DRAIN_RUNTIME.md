# OP-CAP-25 — Controlled Journey Projector Drain Runtime

## Objective

Make Order Journey projections update **automatically** after backend source events (OP-CAP-24 hooks),
without relying on a manual `POST /projection/process` call — and **without** introducing an unsafe daemon,
unmanaged threads, or any new infrastructure. The result is a controlled, bounded, tenant-safe **drain**
layered on top of the existing OP-CAP-23 projector runner.

## Target flow

```
business mutation
  -> source entity saved
  -> OrderJourneyProjectionEvent published (same transaction, OP-CAP-24)
  -> controlled drain picks up pending events
       (bounded, tenant-fair discovery)
  -> OrderJourneyProjectorRunner processes a clamped batch per tenant
       (per-event REQUIRES_NEW, checkpoint/idempotency, retry/backoff/dead-letter)
  -> OrderJourney projection becomes READY
  -> frontend reads backend-owned projection state
```

## No-daemon vs controlled drain — why now

OP-CAP-23 deliberately shipped **no** background processing (explicit/manual only) so the projector could be
proven idempotent and tenant-safe before any automation. OP-CAP-24 added durable events from real mutations
but kept draining manual — pending events did not drain unless someone called the endpoint/runner. That was
acceptable as a foundation but is not a final operating posture. OP-CAP-25 adds automation **without** the
unsafe forms (`while(true)`, raw threads, external queue): a single **config-gated, fixed-delay Spring
scheduled task** that does a **clamped** amount of work per tick and returns.

## Selected runtime pattern

The repository has **no** pre-existing `@Scheduled`/`@EnableScheduling` usage, but it **does** use the
`@ConditionalOnProperty` config-gating convention (`RuntimeRateRedisConfiguration`). So, per the decision
rules, the drain is a **controlled internal runtime service + bounded admin endpoint** (always available),
plus a **config-gated scheduler that is disabled by default**. Actual event processing reuses the existing
`OrderJourneyProjectorRunner` — projector logic is **not** duplicated.

| Component | Type | Responsibility |
| --- | --- | --- |
| `OrderJourneyProjectionDrainService` | `@Service` | `drainTenant` (one tenant), `drainTenants` (cross-tenant), `drainOnce` (configured), `pendingTenantCount`; clamps, tenant-context binding, per-tenant failure isolation |
| `OrderJourneyProjectionEventRepository.findTenantIdsWithPendingEvents` | query | bounded, oldest-first, retry-aware cross-tenant discovery (tenant ids only, never `DEAD_LETTERED`) |
| `OrderJourneyProjectionSchedulingConfiguration` | `@Configuration` | `@EnableScheduling` gated by `enabled=true` |
| `OrderJourneyProjectionScheduledDrain` | `@Component` | `@Scheduled(fixedDelayString=…)` → `drainOnce()`, gated by `enabled=true` |
| `OrderJourneyController` | endpoint | `POST /projection/drain` (REVIEW_ACTION), **current tenant only** |

## Tenant safety

`OrderJourneyService.refreshFromSource` resolves the trusted source row via the **ambient** `TenantContext`,
not the event row's tenant id. The drain therefore binds each tenant's `TenantContext` for the duration of
that tenant's batch and **restores the previous context in `finally`**. This is what lets the system/
scheduled drain (no inbound request tenant) work at all, and guarantees one tenant's context never leaks into
another tenant's processing. Discovery and every runner lookup remain tenant-scoped; event ids are never
trusted across tenants.

## Bounds, failure handling, idempotency

- **Per-tenant batch** clamped by `OrderJourneyProjectionPublisher.clampBatch` (default 25, **max 200**).
- **Tenants per cycle** clamped (`max-tenants-per-cycle`, **hard ceiling 50**); discovery always paged.
- **No starvation**: tenants ordered by oldest drainable event (`min(occurredAt)`).
- **Liveness**: a per-tenant failure is caught and logged with the **error class only** (no tenant id, no
  payload), so one unhealthy tenant never blocks the rest of the cycle.
- **Idempotency / retry / backoff / dead-letter**: unchanged from OP-CAP-23 — owned by the runner's per-event
  `REQUIRES_NEW` checkpoint. `FAILED` retries with a fixed +5m backoff up to 3 attempts, then `DEAD_LETTERED`.
  Dead-lettered events are **excluded by discovery** and never retried. Duplicate draining is a no-op.
- **Response** `OrderJourneyProjectionDrainSummary`: counts/flags only — `tenantsScanned`,
  `eventsProcessed/Skipped/Failed/DeadLettered`, `partial` (tenant scan hit its clamp), `limitApplied`,
  `generatedAt`. No tenant names, customer data, or raw payload.

## Configuration (`orderpilot.runtime.order-journey-projection`)

| Property | Default | Notes |
| --- | --- | --- |
| `enabled` | `false` | When `false`: **no** scheduling infra, **no** scheduled bean — explicit/manual only. |
| `batch-size` | `25` | Per-tenant batch; clamped ≤200 in code even if mis-set. |
| `max-tenants-per-cycle` | `10` | Tenants per cycle; clamped ≤50 in code. |
| `fixed-delay-ms` | `30000` | Scheduled drain fixed (and initial) delay. |

Safe defaults; clamped even if configured too high; no secrets; no environment-specific hardcoding. Tests
never enable it, so the no-daemon default holds in CI.

## Endpoints

- **Retained**: `POST /api/v1/order-journeys/projection/process?limit=` (OP-CAP-23) — unchanged.
- **New**: `POST /api/v1/order-journeys/projection/drain?perTenantLimit=` (REVIEW_ACTION via the existing
  `ApiPermissionInterceptor` prefix rule) — drains **only the current `X-Tenant-Id`**. No endpoint drains all
  tenants; the cross-tenant path is reserved for the system scheduler.
- **Extended (cheap)**: `GET /projection-health` now also returns `oldestPendingAt`, `schedulerEnabled`, and
  `configuredBatchSize` for staleness/monitoring — single construction site, no heavy joins.

## ON_READ_FALLBACK retirement plan

`by-source` still returns `ON_READ_FALLBACK` when no projection exists yet — **retained for compatibility,
documented as temporary**. Retire only when: (1) all major source create/update paths publish events;
(2) the controlled/scheduled drain is enabled in the target environment; (3) missed-event recovery exists;
(4) production monitoring of `projection-health` is in place; (5) measured fallback usage is near zero.

## Security notes

- Tenant isolation preserved and reinforced (per-tenant context binding + restore; tenant-scoped discovery
  and processing; event ids never trusted across tenants) — tested.
- Permission contracts preserved: the drain endpoint is non-GET under `/api/v1/order-journeys` → REVIEW_ACTION
  (tested). No GET contract changed.
- Audit conventions preserved: the audited `projection-requests` path is unchanged; drain reuses the runner.
- No raw payloads/secrets in discovery, responses, health, or logs; logs carry counts + error class only.
- No external/ERP/1C/PSP/bank/carrier/AI writes; no fake paid/shipped/delivered/GPS/carrier state; payment
  milestones remain `UNKNOWN` (tested).

## Performance notes

- Discovery is a single bounded, indexed, tenant-grouped query returning **tenant ids only** (no event/payload
  load), clamped by `Pageable`.
- Per cycle: ≤ `max-tenants-per-cycle` tenants × ≤ clamped batch events; each event in its own short
  transaction. No unbounded scan, no N+1 over events.
- Disabled by default: zero scheduler threads in the normal posture.

## Tests

- `OrderJourneyProjectionDrainServiceTest` (12): draft-quote and draft-order drain → READY; `perTenantLimit`
  bound; `tenantLimit` bound + `partial`; duplicate drain idempotent (one journey/milestone); a failing event
  in one tenant does not block another in the same cycle; invalid event → bounded `FAILED`; `DEAD_LETTERED`
  neither discovered nor retried; health reports pending/oldest/scheduler/batch; by-source after drain →
  `READY` (not `ON_READ_FALLBACK`); no fabricated payment milestones; scheduler bean absent by default.
- `OrderJourneyProjectionScheduledDrainConfigTest` (1): scheduler bean registered and drains cleanly when
  `enabled=true`.
- `ApiPermissionInterceptorPermissionTest` (+2 → 135): `projection/drain` requires REVIEW_ACTION.
- Regression green: `OrderJourneyProjectionServiceTest` (10), `OrderJourneySourceHookStage24Test` (7),
  `OrderJourneyServiceTest` (6).

## Non-goals

Direct AI↔frontend integration; AI-authored journey state; PSP/bank payment milestones or real payment
integration; customer public tracking links; carrier/TMS/WMS; GPS/maps/routes; connector/ERP/1C writes;
Kafka/Redpanda/RabbitMQ/new queue infra; unmanaged background threads; broad runtime rewrite; global
technical rename. The manual `projection/process` endpoint and `ON_READ_FALLBACK` are **not** removed.

## Next recommended stage

OP-CAP-26 — missed-event recovery + production drain monitoring (alerting on `oldestPendingAt`/dead-letter
growth), enabling the staged retirement of `ON_READ_FALLBACK` per the plan above.

## Confirmations

- No direct AI-to-frontend integration was added.
- No external queue / new infrastructure was added (config-gated Spring scheduling only; no Kafka/Redis/queue).
- No backend technical rename was done (`com.orderpilot`, `/api/v1`, `X-OrderPilot-Permissions`, table and
  migration names unchanged).

# OP-CAP-21 — Transaction Command Center Read Models & Operant UX API Projection Layer

## Objective

Turn the Operant frontend shell into a backend-backed operations cockpit by adding safe,
tenant-scoped, read-only projection APIs for the command center, work queue, runtime/outbox health,
audit timeline, and reconciliation preview. No new business mutations.

## What shipped

### Backend
- `CommandCenterController` — `GET /api/v1/command-center/summary` (read-only, GET-only prefix).
- `CommandCenterReadService` — bounded read aggregation (count queries, `findTop20…`, `Page(0,N)`).
- `CommandCenterDtos` — summary + metric, work-queue, runtime/outbox health, audit, reconciliation.
- `ApiPermissionInterceptor` — `/api/v1/command-center` → `ANALYTICS_READ`.
- Bounded repository additions:
  - `AuditEventRepository.findTop20ByTenantIdOrderByOccurredAtDesc`
  - `OutboxEventRepository.countByTenantIdAndStatus` + `findFirstByTenantIdAndPublishedAtIsNotNullOrderByPublishedAtDesc`
  - `ProcessingJobRepository.countByTenantIdAndStatus` + `findFirstByTenantIdOrderByQueuedAtDesc`
  - `ExceptionCaseRepository.findTop20ByTenantIdAndStatusInOrderByCreatedAtDesc`, `countByTenantIdAndStatusIn`, `countByTenantIdAndSeverityIn`
  - `DraftQuoteRepository.countByTenantId`

### Frontend
- `lib/command-center-api.ts` — typed read-only client (tenant + `ANALYTICS_READ` headers).
- `components/operant-command-center.tsx` — backend-backed cockpit sections with honest states.
- `app/(dashboard)/command-center/page.tsx` — wires the component; Operant shell preserved.

## Data authority

Derived read models only. Source of truth stays in existing domain tables/services. No projection
table was created this stage (existing indexed tables + bounded queries suffice).

## Security / performance notes

- Tenant-scoped on every query; audit preview excludes the `metadata` JSON blob structurally.
- No external/AI/connector calls; no writes; no secrets or raw payloads.
- All lists bounded; count-based metrics; no full-table scans or synchronous heavy recompute.

## Tests

- Backend: `CommandCenterReadServiceTest` (5) — tenant isolation, bounded work-queue preview, audit
  metadata exclusion, runtime/outbox failure representation, honest empty/unavailable states.
  `ApiPermissionInterceptorPermissionTest` (+2 → 123) — `ANALYTICS_READ` required.
- Frontend: `tests/command-center-read-models.test.mjs` (7) — endpoint/tenant/permission boundary,
  section rendering, honest empty/partial/unavailable states, no mutation controls, no raw metadata,
  Operant branding preserved.

## Known limitations / non-scope

- No payment/PSP/bank reconciliation; only inventory reconciliation is real.
- `automationReadiness` is intentionally `available=false` (no backing model yet).
- Settings Hub left as-is (no mutating runtime controls added this stage).
- No backend technical rename to Operant.

## Next recommended stage

Materialized/rebuildable command-center projection (checkpointed read-model table) if request-path
aggregation cost grows, plus a real automation-readiness signal.

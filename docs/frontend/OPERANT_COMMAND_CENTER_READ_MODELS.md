# Operant Command Center Read Models (OP-CAP-21)

Read-only, tenant-scoped projection layer that turns the Operant command center shell into a
backend-backed operations cockpit. No business mutations are introduced.

## Endpoint

`GET /api/v1/command-center/summary`

- Tenant-scoped via `X-Tenant-Id` / `TenantContext.requireTenantId()`.
- Permission: `ANALYTICS_READ` (enforced by `ApiPermissionInterceptor` for the whole
  `/api/v1/command-center` prefix; the surface is GET-only).
- Read-only. No external/AI/connector calls, no writes.
- Errors flow through the existing `GlobalExceptionHandler`.

## DTOs (`CommandCenterDtos`)

`CommandCenterSummaryDto` aggregates:

- `metrics`: `CommandCenterMetricDto[]` — `pendingReviews`, `highRiskCases`, `draftQuotes`,
  `draftOrders`, `outboxPending`, `jobsFailed`, and `automationReadiness`.
- `workQueue`: `WorkQueuePreviewDto` + `WorkQueueItemDto[]` — bounded preview of recent open
  operator-review cases.
- `runtime`: `RuntimeHealthDto` — processing-job pending/running/failed counts + last queued time.
- `outbox`: `OutboxHealthDto` — outbox pending/published/skipped counts + last published time.
- `auditTimeline`: `AuditTimelinePreviewDto` + `AuditTimelineItemDto[]` — bounded recent audit rows.
- `reconciliation`: `ReconciliationPreviewDto` + `ReconciliationPreviewCaseDto[]` — inventory
  reconciliation summary + recent cases.

Each section carries a `generatedAt` timestamp and `available` / `partial` flags.

## Data authority

Read models are **derived data**. The source of truth remains the existing domain tables/services:
`exception_case`, `draft_quote`, `draft_order`, `processing_job`, `outbox_event`, `audit_event`,
and `reconciliation_case`. The query service (`CommandCenterReadService`) only reads — via count
queries, `findTop20…` preview windows, and `Page` requests with explicit small page sizes. There
are no full-table scans, unbounded joins, or synchronous heavy recomputation on the request path.

## Partial / unavailable states

The projection never fabricates values:

- `CommandCenterMetricDto.available = false` (e.g. `automationReadiness`) means there is **no real
  data source yet**; the frontend renders "Unavailable", never a fake number.
- `RuntimeHealthDto.available = false` / `OutboxHealthDto.available = false` means no jobs / no
  outbox events exist for the tenant.
- `…partial = true` means the response is a bounded preview (e.g. more open cases exist than the
  20-row work-queue window, or the audit window is full).
- `RuntimeHealthDto.degraded` / `OutboxHealthDto.degraded` flag failed jobs / pending outbox
  backlog for operator attention.

## Security constraints

- Tenant isolation on every query.
- Audit timeline exposes identifiers + action type only. The `audit_event.metadata` JSON blob is
  **structurally excluded** from `AuditTimelineItemDto` (no field, no accessor).
- No secrets, raw AI prompts, raw document/message payloads, or raw payment-sensitive data.
- All list responses are bounded (preview windows / explicit page sizes).

## Frontend

- `lib/command-center-api.ts` — typed client. Sends `X-Tenant-Id` and `X-OrderPilot-Permissions:
  ANALYTICS_READ`. GET-only; returns `{ data: null, error }` on failure (no thrown errors, no
  fabricated data). The `X-OrderPilot-Permissions` header keeps the existing technical API contract
  (the technical `OrderPilot` namespace is intentionally **not** renamed).
- `components/operant-command-center.tsx` — server component rendering metrics, work queue,
  runtime/outbox health, audit timeline, and reconciliation preview with honest
  loading/empty/partial/unavailable states. No mutation controls.
- Wired into `app/(dashboard)/command-center/page.tsx`, preserving the Operant dark shell and
  grouped navigation.

## Intentionally not implemented

- No payment-provider / PSP / bank reconciliation (only inventory reconciliation is real).
- No automation-readiness percentage (returned as unavailable until a real readiness model exists).
- No mutating settings forms or business actions.
- No new AI calls, AI-memory writes, connector/ERP writes, or new infrastructure.
- No backend technical rename of `OrderPilot` identifiers.

## Future projection evolution

- A dedicated rebuildable read-model/projection table (with checkpoint + tests) if request-path
  aggregation cost grows.
- A real automation-readiness signal once a backing model exists.
- Optional cursor pagination on the work-queue/audit previews if deep paging is needed.

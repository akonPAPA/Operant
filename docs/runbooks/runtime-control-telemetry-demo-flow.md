# Runtime Control Telemetry — RFQ/AI/demo path (OP-CAP-27D)

Read-only, tenant-scoped visibility into the PR #244 runtime-control posture that protects the
RFQ/AI/demo flow. This surface answers a single operator question: **is the demo flow protected by
runtime-control gates, and what is measured vs. not measured?**

It is a *read model*. It never invokes the admission guard, never calls a connector, never performs an
external write, and never fabricates a metric.

## Endpoint

- `GET /api/v1/runtime-control/demo-flow`
- Permission: `ANALYTICS_READ` (operator-facing read). It is **not** `RUNTIME_ENTITLEMENT_READ` — the
  `/api/v1/runtime-control` route rule is deliberately ordered before the `/api/v1/runtime` entitlement
  rule so a telemetry reader can never inherit runtime-governance authority. No Operant staff/support
  permission satisfies it either.
- Tenant scope: resolved server-side from `X-Tenant-Id` (`TenantContext`). The tenant id is **not**
  returned in the response.
- Request contract: no `@RequestParam` / `@RequestBody`. A client cannot supply tenant, actor, source,
  status, or runtime authority — any smuggled query params or body are inert.

## What the response contains

- `safety` — explicit framing: `runtimeControlView=READ_ONLY`, `connectorInvocation=NOT_INVOKED`,
  `externalExecution=DISABLED`, `guardEvaluation=NOT_INVOKED_BY_THIS_READ`,
  `telemetryCompleteness=PARTIAL`.
- `workloadPostures[]` — the four demo-path checkpoints (`DEMO_RFQ_HANDOFF_CREATE`,
  `RFQ_HANDOFF_AI_ADVISORY`, `RFQ_HANDOFF_DRAFT_QUOTE_CREATE`, `RFQ_HANDOFF_DEMO_DECISION`), each with a
  workload type, sync/async posture, cheap-vs-AI cost path, and guard tier. The three deterministic demo
  ops are `RATE_BACKPRESSURE_GATED` (cheap path); the AI advisory op is `ENTITLEMENT_QUOTA_RATE_GATED`
  (AI path, guarded by the shared `AI_VALIDATION_EXPLANATION` guard).
- `admission` — `runtimeControlEnabled` / `aiWorkloadEnabled` / `maxCostUnitsPerRequest` /
  `maxSyncCostUnits` / `backpressureQueueDepth` are `STATIC_CONTRACT` values read from
  `RuntimeControlProperties`; `admittedCount` / `deniedCount` are `NOT_MEASURED`.
- `provenGuarantees[]` — which runtime-control guarantees hold for the demo path.
- `notMeasured[]` — what is explicitly out of scope.

### Measurement labels

Every metric cell is a `TelemetryValue { kind, value, explanation }` where `kind` is one of:

| kind | meaning | value |
| --- | --- | --- |
| `MEASURED` | genuinely observed/counted from persisted state | display string |
| `STATIC_CONTRACT` | deterministic contract/config posture | display string |
| `NOT_MEASURED` | not persisted/aggregated in this slice | `null` (never a fake zero) |
| `NOT_APPLICABLE` | dimension does not apply to the demo path | `null` |

**Why the counters are `NOT_MEASURED`:** `RuntimeControlService` is deterministic and
side-effect-free — it records no admission/denial counters. Rather than display `0` (which would falsely
imply "zero denials happened"), admitted/denied counts are honestly labelled `NOT_MEASURED`.

## Dashboard

- Route: `/runtime-control` (nav: *Intelligence → Runtime Control Telemetry*).
- The panel renders the safety banner ("Read-only runtime-control view · no connector invoked · external
  execution disabled · telemetry may be partial"), the per-step posture table, the admission table (with
  honest "Not measured" / "Not applicable" labels), the proven guarantees, and the not-measured section.
- It never renders raw JSON, `<pre>` dumps, stack traces, tenant/actor/idempotency/prompt/payload/
  connector internals. Backend errors are mapped to bounded safe copy.

## Safety boundary

- Read-only. No business mutation, quote/order approval, AI action generation, connector invocation,
  ERP/1C execution, ChangeRequest creation, or outbox external execution.
- No tenant/actor/source/status/runtime authority accepted from the client.
- No support/staff route expansion; this is a tenant-operator read.

## What is NOT proven by this slice

- Real production distributed runtime telemetry (multi-node runtime-guard behaviour).
- Provider-specific runtime billing / accounting.
- Runtime denial telemetry for all channels (only the RFQ/AI/demo path is described).
- A support/staff runtime telemetry plane.
- OLAP / warehouse metrics, export, or time-range scope.
- Production SSO/auth.
- Real ERP/1C/connector execution.

## Verification

- Backend: `mvn -f apps/core-api/pom.xml -Dtest="RuntimeControlTelemetryControllerTest,RuntimeControlDemoFlowTelemetryServiceTest,ApiRouteSecurityClassificationTest,ResponseDtoLeakContractTest,CommerceIntelligenceControllerTest,ApiPermissionRoleMatrixTest,ControllerEntityReturnBanTest" test`
- Frontend: `node --test apps/web-dashboard/tests/runtime-control-telemetry.render.test.mjs` plus
  `npm run typecheck` / `npm run lint` / `npm run build`.

# Commerce Intelligence Demo-Flow Read Model

## Purpose

Commerce Intelligence makes the existing tenant RFQ demo flow visible as operator product value:

```text
RFQ handoff
-> AI Work advisory suggestion
-> operator review
-> review-required draft quote
-> safe demo terminal decision
```

It is a read model only. It does not create, approve, send, sync, export, execute, automate, or
mutate business records.

## Endpoint and permission

```http
GET /api/v1/commerce-intelligence/demo-flow
X-Tenant-Id: <trusted tenant context when required by the deployment>
X-OrderPilot-Permissions: ANALYTICS_READ
```

- Request body: none.
- Query parameters: none.
- Tenant scope: resolved by `TenantContext.requireTenantId()`.
- Permission: tenant-operator `ANALYTICS_READ`.
- External customer access: none.
- Service-account use: not an AI worker or connector route; any service principal would still need
  the normal trusted tenant context and `ANALYTICS_READ`.
- Operant support/staff access plane: not used. `STAFF_*` permissions do not grant this route.

## Response

The top-level response contains:

- `generatedAt`: server generation time.
- `windowLabel`: explicitly describes the retained-record scope; this PR has no client-selected
  date range.
- `summary`: tenant-scoped counts.
- `safety`: stable demo contract markers plus honest measurement scope.
- `runtimeControl`: safe PR #244 posture labels only.
- `bottlenecks`: grouped open blocking issue codes from RFQ-handoff draft quotes.
- `recentFlows`: at most 10 latest RFQ handoffs with bounded previews and allowlisted status facts.
- `notProven`: limitations that must not be inferred as product or production proof.

No response field contains tenant ids, actor/reviewer/creator/decider ids, inbound event ids, channel
connection ids, audit ids, idempotency keys, correlation ids, internal runtime keys/buckets,
provider payloads, prompts, tokens, secrets, credentials, stack traces, raw errors, or retry
internals.

The `handoffId` in `recentFlows` is the opaque workflow handle already used by the tenant RFQ
operator workspace. Request text is normalized and capped at 180 characters.

## Summary metric meanings

| Metric | Meaning | Does not mean |
|---|---|---|
| `rfqHandoffsTotal` | Retained RFQ handoffs for the current tenant | Real orders or customer commitments |
| `pendingReviewCount` | Handoffs in `PENDING_REVIEW` | Approved requests |
| `inReviewCount` | Handoffs in `IN_REVIEW` | Approved quotes |
| `convertedCount` | Handoffs marked `CONVERTED` in the internal review workflow | Real sales, orders, invoices, or ERP conversion |
| `dismissedCount` | Handoffs dismissed by an operator | Customer rejection or lost revenue |
| `aiAdvisorySuggestionsCount` | AI Work suggestions whose source is an RFQ handoff | Autonomous action or AI approval |
| `reviewRequiredDraftQuotesCount` | RFQ-handoff draft quotes with human review required | Approved or customer-sent quotes |
| `safeTerminalDemoDecisionsCount` | RFQ-handoff drafts in `DEMO_COMPLETED` or `DEMO_DECLINED` | Production conversion |
| `demoCompletedCount` | Safe demo completion marker | Sale, revenue, invoice, ERP sync, or customer commitment |
| `demoDeclinedCount` | Safe demo decline marker | Customer rejection or recognized loss |

All count queries include the current tenant. Recent lists and supporting bulk reads are bounded.
No revenue, order value, recognized revenue, production conversion, or customer-commitment metric
is inferred.

## Safety semantics

The demo workflow contract states:

- `externalWriteStatus = DISABLED`
- `connectorCallStatus = NOT_INVOKED`
- `outboxStatus = NOT_REQUESTED`

These are stable path semantics from the existing demo flow. They are not database row-count
measurements. In this PR:

- `observedConnectorCommandRows = null`
- `observedChangeRequestRows = null`
- `observedOutboxRows = null`
- `measurementScope = NOT_MEASURED`

The API deliberately returns `null`/`NOT_MEASURED`; it never invents a zero or labels all-tenant
records as demo-flow-only evidence.

## Runtime-control semantics

PR #244 guards the existing path before side effects. Commerce Intelligence reports only:

- demo RFQ creation: `RATE_BACKPRESSURE_GATED`
- RFQ advisory generation: `AI_VALIDATION_EXPLANATION_GUARDED`
- draft quote creation: `RATE_BACKPRESSURE_GATED`
- safe demo decision: `RATE_BACKPRESSURE_GATED`
- demo-operation billing/quota dimension: `NOT_APPLICABLE_FOR_DEMO_OPS`
- denial telemetry: `NOT_MEASURED`

The read endpoint does not invoke `RuntimeGuardService`. It does not expose quotas, feature
entitlements, tenant plans, rate buckets, Redis keys, thresholds, nonce/jti values, retry-after
internals, raw decisions, or exceptions. The three demo operations remain rate/backpressure-only.
The shared AI advisory boundary continues to use the existing `AI_VALIDATION_EXPLANATION` guard.

## Bottlenecks and recent flows

Bottlenecks group actual open, blocking `QuoteValidationIssue.issueCode` values for draft quotes
whose source type is `RFQ_HANDOFF`. No issue detail JSON or internal message is returned.

Recent flows show only:

- opaque handoff handle;
- source channel;
- bounded request preview;
- detected intent;
- handoff status;
- AI suggestion status, public schema version, and risk label;
- draft and validation status;
- safe terminal marker;
- open blocking issue codes;
- created/updated times.

An RFQ handoff, AI suggestion, draft quote, and safe terminal marker remain distinct facts.
`SAFE_DEMO_TERMINAL` is not quote approval.

## Relationship to existing surfaces

- `/demo` creates or reuses the deterministic demo RFQ handoff through the backend-owned demo path.
- `/channels/rfq-handoffs` remains the operator review workspace.
- AI Work Schema V1 remains the public advisory contract; Commerce Intelligence derives only its
  stable schema identifier and never reads or returns provider payload JSON.
- PR #244 remains the enforcement owner for runtime guard placement and denial behavior. This read
  model only explains the safe posture.

## External-write prohibition

The controller has one GET route. The service is `@Transactional(readOnly = true)` and calls count
or bounded read repository methods only. It creates no audit, idempotency, connector command,
connector sandbox execution, change request, outbox event, quote approval, order, message, or
external write.

## `notProven`

`notProven` is an explicit anti-inference boundary. It currently records:

- external-write row counts are not measured by this endpoint;
- runtime denial telemetry is not aggregated;
- distributed/multi-node runtime guard behavior is not proven here;
- production conversion/revenue is not measured.

## Deferred work

- analytics warehouse / OLAP;
- production-grade bounded time-range filtering;
- export;
- advanced KPI model;
- real revenue/conversion metrics;
- cross-channel non-demo intelligence;
- staff/support intelligence plane;
- live PostgreSQL/browser proof for this route if not run;
- runtime denial telemetry dashboard;
- distributed runtime-guard telemetry;
- provider-specific runtime accounting.

# RFQ / AI / Demo Path Runtime Control (PR #244, OP-CAP-27B)

Runtime-control foundation around the already-existing visible RFQ/AI/demo flow:

```
/demo -> deterministic Telegram RFQ handoff -> /channels/rfq-handoffs
     -> AI Work advisory suggestion -> operator review
     -> review-required draft quote -> COMPLETE_DEMO / DECLINE_DEMO
     -> SAFE_DEMO_TERMINAL -> audit recorded
     -> externalExecution=DISABLED, connectorCall=NOT_INVOKED, outbox=NOT_REQUESTED
```

This PR makes that path **runtime-aware and denial-safe**. It adds no autonomous AI action, no
external write, no billing/plan model, no analytics, and no support/staff plane. It reuses the
existing OP-CAP-16C/D/E/F runtime guard (`RuntimeGuardService`: entitlement → quota → rate) — it does
**not** introduce a parallel runtime-control system.

## Non-negotiable law preserved

AI suggests. Rules validate. Human approves if risky. Backend writes. Audit records.
Runtime control gates expensive/risky work **before** provider/business-workflow execution.
**Denial fails closed and mutates no state.**

## Covered operations

| Path step | Operation type (`RuntimeOperationType`) | Feature gate | Metric / quota | Guard call | Weight / budget |
|---|---|---|---|---|---|
| Demo RFQ handoff creation | `DEMO_RFQ_HANDOFF_CREATE` | none (no entitlement/plan coupling) | none (`NO_POLICY`) | `RuntimeGuardService.enforce(request)` | 2 / 60 per 60s |
| RFQ handoff AI advisory suggestion | `AI_VALIDATION_EXPLANATION` (shared advisory-generation boundary) | `AI_VALIDATION_EXPLANATION` | `AI_INPUT_UNITS` | `enforce(request, feature)` in `AiWorkService.createSuggestion` (unchanged, from PR#243/16G) | 4 / 60 |
| Draft quote creation from RFQ handoff | `RFQ_HANDOFF_DRAFT_QUOTE_CREATE` | none | none (`NO_POLICY`) | `enforce(request)` | 3 / 60 |
| Safe demo terminal decision | `RFQ_HANDOFF_DEMO_DECISION` | none | none (`NO_POLICY`) | `enforce(request)` | 2 / 60 |

### Why the AI suggestion is not a new operation type

`AiWorkService.createSuggestion` is a **shared** advisory-generation service (RFQ handoff, operator
review, channel message, …). It is already guarded at the single boundary immediately before the
provider call by `AI_VALIDATION_EXPLANATION` (OP-CAP-16G). Branching that shared service by source
type to mint an `RFQ_HANDOFF_AI_SUGGESTION` operation would add parasite complexity to a shared path
for no safety gain. The RFQ handoff advisory path is proven to flow through that guard by
`AiWorkExplanationGuardStage16GTest#rfqHandoffAdvisoryDenialDeniesBeforeProvider`. The three
**demo/quote** boundaries are semantically distinct and each gets its own operation type.

### Why the three demo operations have no feature/quota

The demo path is deliberately **rate/backpressure gated only** (no `RuntimeFeatureType`, no
`UsageMetricType`). This keeps the demo denial-safe without introducing plan/entitlement/billing
semantics (explicitly out of scope). A denial on these operations therefore comes from the weighted
per-tenant fixed-window rate limiter (HTTP 429). Estimated units are a fixed `1` per call because
cost is governed by the operation weight, not payload size (there is no quota metric to size).

## Guard placement (before any side effect)

- **Demo RFQ creation** — `LocalDemoRfqIntakeService.createOrGet`: guard runs immediately after
  tenant resolution, **before** the channel bridge (`ChannelBotRuntimeBridgeService.handleInbound`),
  the inbound event, the `channel_rfq_handoff` row, the bot conversation, and any audit event.
- **Draft quote creation** — `RfqHandoffDraftQuoteService.createDraftQuote`: guard runs **after** the
  idempotency short-circuit (an existing draft is returned without consuming guard budget) and after
  the `IN_REVIEW` state check, **before** `draft_quote` / `draft_quote_line` / `quote_validation_issue`
  creation and the handoff `CONVERTED` transition.
- **Safe terminal decision** — `RfqHandoffDraftQuoteService.decide`: guard runs **after** all
  validation lookups, **before** the quote status transition, handoff terminal state, and the
  `RFQ_HANDOFF_DEMO_DECISION_RECORDED` audit record. The controller wraps `decide` in
  `IdempotencyService.execute`, so a replayed decision returns the stored result without re-invoking
  `decide` and consumes no guard budget.

## Denial semantics

- **Status mapping** (existing, unchanged): rate/backpressure → **429** `RUNTIME_RATE_LIMITED`
  (with `Retry-After`); quota/feature → **403** `RUNTIME_QUOTA_EXCEEDED` /
  `RUNTIME_FEATURE_NOT_AVAILABLE`. For the three demo operations only 429 is reachable today.
- **Safe body**: `GlobalExceptionHandler` emits a stable `ApiErrorResponse` code only. No quota key,
  Redis key, rate bucket, nonce/jti, tenant plan, threshold, exception class, or stack trace is
  exposed. The frontend maps 429/503 to a bounded message ("Runtime capacity is busy right now.
  Please retry this RFQ action in a moment.") and never surfaces raw backend bodies.
- **No provider invocation** on the AI path denial (the guard precedes `provider.generate`).
- **No business mutation**: denial throws before any repository write; the enclosing `@Transactional`
  rolls back, so no handoff / draft quote / draft line / validation issue / decision state is written.
- **No audit on denial**: the guard runs before every audit call on these paths; no safe-denial audit
  is intentionally recorded (none is designed for these operations).
- **No external write**: no `connector_command`, `connector_sandbox_execution`, `change_request`, or
  `outbox_event` is created on any path, allowed or denied.

## Idempotency / replay

- **First-time denial creates no success state.** For the decision path the controller reserves an
  idempotency row inside the same transaction as `decide`; a guard denial rolls the transaction back,
  removing the reserved row — a repeated denied request starts fresh and cannot replay a fake success.
- **Successful replay consumes no new guard budget.** Draft-quote creation returns the existing draft
  before the guard; the decision replay returns the stored response via `IdempotencyService` without
  re-entering `decide`.
- Demo RFQ creation has no pre-guard idempotency short-circuit (its dedup lives inside the channel
  bridge), so each call is guarded; this is intended and safe.

## What is allowed vs denied

- **Allowed**: the full documented demo flow continues to reach `SAFE_DEMO_TERMINAL` with the same
  review-required draft quote, the same audit trail, and the same `externalExecution=DISABLED` /
  `connectorCall=NOT_INVOKED` / `outbox=NOT_REQUESTED` markers.
- **Denied**: a rate-limited tenant is failed closed at the checkpoint with a safe 429; no advisory
  row, no draft, no terminal decision, and no external record is produced.

## Relationship to PR #243 (AI Work Schema V1)

PR #244 does not touch the AI Work Schema V1 contract. `AiWorkService.createSuggestion` still
enforces the advisory guard before the provider, still returns `schemaVersion` with allowlisted
fields, still fails closed on bad provider payload, never returns raw provider payload, and preserves
advisory-only safety markers. The RFQ handoff AI path simply reuses that guarded boundary.

## What remains not proven (deferred)

See `docs/backlog/fix-notebook.md` (OP-CAP-27B item). Not covered here: production billing/plan
model; a global product workload taxonomy; multi-tenant quota dashboards; operator-visible runtime
analytics; distributed/multi-node runtime-guard proof; a live PostgreSQL/browser proof of the
**denial** path (allowed path already has PostgreSQL/browser proof — see
`post-pr239-real-demo-proof.md`); and provider-specific runtime accounting.

## Guidance for PR #245 (Commerce Intelligence)

Commerce Intelligence must consume only **safe read-model / runtime evidence**:

- **May read**: stable tokens — operation/workload type names, `ALLOWED`/`DENIED` decision, the
  `backpressure`/`OK` posture, safe error codes, and audited terminal state markers.
- **Must remain hidden**: `tenantId`/`actorId` internals in UI, quota bucket IDs, Redis/rate keys,
  nonce/jti, tenant plan internals, exact thresholds, retry-after internals, raw guard state, raw
  provider payloads, prompts, tokens, and stack traces.
- **Must not assume**: that the demo operations carry a quota/feature/billing dimension (they do
  not), or that denial produces any audit/business/external record (it does not). CI must treat these
  operations as rate/backpressure gated only and must not infer plan/entitlement state from them.

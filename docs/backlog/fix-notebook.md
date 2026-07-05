# Fix Notebook

## P2 — Runtime-control foundation for the RFQ/AI/demo path (OP-CAP-27B)

- Status: PARTIAL (2026-07-05, PR #244) — the runtime-control **foundation** is CLOSED. All four
  RFQ/AI/demo checkpoints are guarded before side effects, denial fails closed with no business/audit/
  external-write mutation, idempotency/replay remains correct, and safe 429/503 messaging is bounded.
  A small set of production-grade items remain deferred (below).
- Severity: P2 / Runtime Safety and Cost Control
- Covered / files:
  - `apps/core-api/src/main/java/com/orderpilot/application/services/runtime/RuntimeOperationType.java`
    (added `DEMO_RFQ_HANDOFF_CREATE`, `RFQ_HANDOFF_DRAFT_QUOTE_CREATE`, `RFQ_HANDOFF_DEMO_DECISION`)
  - `apps/core-api/src/main/java/com/orderpilot/application/services/runtime/EndpointWeightPolicy.java`
    (weights/budgets for the three demo operations; no quota metric — rate/backpressure only)
  - `apps/core-api/src/main/java/com/orderpilot/application/services/channel/LocalDemoRfqIntakeService.java`
    (guard before demo RFQ handoff creation)
  - `apps/core-api/src/main/java/com/orderpilot/application/services/channel/RfqHandoffDraftQuoteService.java`
    (guard before draft quote creation and before the safe terminal decision)
  - `apps/web-dashboard/lib/rfq-handoff-api.ts` (bounded 429/503 runtime-denial message)
  - `docs/runbooks/rfq-ai-demo-runtime-control.md` (full guard/denial/idempotency/PR#245 doc)
  - Tests: `LocalDemoRfqIntakeRuntimeGuardStage27BTest`, `RfqHandoffDraftQuoteRuntimeGuardStage27BTest`,
    `AiWorkExplanationGuardStage16GTest#rfqHandoffAdvisoryDenialDeniesBeforeProvider`,
    `apps/web-dashboard/tests/rfq-handoffs.test.mjs`
- Root cause addressed: demo RFQ creation, draft-quote creation, and the terminal decision previously
  ran with no runtime admission control — only the AI advisory boundary was guarded (OP-CAP-16G). An
  expensive/abusive burst could drive the demo/quote writes without any backpressure gate.
- Decisions documented: the AI suggestion boundary intentionally reuses the shared
  `AI_VALIDATION_EXPLANATION` guard (not a parallel op); the three demo operations are rate/backpressure
  gated only (no feature/quota/plan) to avoid billing coupling.
- Residual / deferred items (each PARTIAL, not required for the foundation):
  - Live PostgreSQL + browser proof of the **denial** path (the allowed path already has PostgreSQL/
    browser proof in `post-pr239-real-demo-proof.md`). Risk: low — denial is unit/service proven and
    fails closed within a rolled-back transaction. Suggested proof: exhaust the per-tenant rate window
    against a disposable PostgreSQL demo DB and assert 429 + zero handoff/draft/decision/external rows.
  - Production billing/plan model and per-tenant quota dimension for the demo operations. Risk: low —
    intentionally excluded; must go through a separate pricing/security review.
  - Global product workload taxonomy + operator-visible runtime analytics / quota dashboards. Risk:
    low — deferred to Commerce Intelligence (PR #245); must read only safe runtime evidence.
  - Distributed / multi-node runtime-guard proof (current rate store is per-instance in-memory by
    default). Risk: medium at multi-node scale. Suggested fix: shared rate store + a multi-node
    admission test before horizontal scale-out.
  - Provider-specific runtime accounting (future model-provider integration). Risk: low; deferred to
    the AI runtime owners under a separate review.
- Owner/target week: Runtime/platform owners; unscheduled.

## P2 — AI Work generic/heuristic public schema

- Status: PARTIAL (2026-07-05, PR #243) — the V1 public projection is CLOSED. Four exact schema
  identifiers, schema-specific validation, bounded/redacted allowlisted DTOs, fail-closed malformed
  output, typed frontend rendering, and RFQ advisory safety markers are implemented and covered by
  targeted backend/frontend tests.
- Severity: P2 / Contract Stability and Data Boundary
- Files:
  - `apps/core-api/src/main/java/com/orderpilot/application/services/aiwork/AiWorkPublicResponseMapper.java`
  - `apps/core-api/src/main/java/com/orderpilot/api/dto/AiWorkDtos.java`
  - `apps/web-dashboard/lib/ai-work-api.ts`
  - `apps/web-dashboard/components/ai-work-schema-v1-view.tsx`
  - `docs/api/ai-work-schema-v1.md`
- Root cause: the prior public projection inferred optional fields from unversioned provider JSON.
  Malformed structured output could still leave generic generated text available as the summary,
  so consumers lacked a stable schema discriminator and a strict fail-closed contract.
- Risk closed in PR #243: frontend consumers no longer depend on unversioned/heuristic shapes, raw
  provider output is never a fallback, and unknown or secret-like provider fields are not emitted.
- Residual tasks (not required for V1 public safety):
  - provider-specific structured-output versions behind the internal provider interface;
  - a richer evidence model after product/API review;
  - an optional evaluation corpus for future provider conformance;
  - future model-provider integration under a separate security/runtime review.
- Required future proof/tests: provider contract tests per provider/version, evidence compatibility
  tests, evaluation thresholds, and provider-specific timeout/error/redaction tests.
- Owner/target week: Product/API and AI runtime owners; unscheduled.

## P2 Product Decision — unify the legacy demo RFQ entrypoint with the channel handoff workspace

- Status: CLOSED (2026-07-04, PR #242) — implementation and bounded H2/frontend proof already passed;
  the residual live browser + PostgreSQL proof is now complete. A disposable PostgreSQL database
  (`operant_post_pr239_proof` on `localhost:15432`) was started, the two-test PostgreSQL integration
  command passed (4 tests), and a real headless browser drove the full `/demo` double-click ->
  `/channels/rfq-handoffs` walkthrough to `SAFE_DEMO_TERMINAL`. Tenant-scoped PostgreSQL queries
  confirmed exactly one `PENDING_REVIEW` handoff after repeated clicks, terminal `draft_quote`
  `DEMO_COMPLETED` with `requires_human_review=true`, one decision audit, one SUCCEEDED idempotency
  row, and zero connector/sandbox/change_request/outbox rows. Full evidence:
  `docs/runbooks/post-pr239-real-demo-proof.md` section 14A. One bounded UX defect found and fixed in
  the same PR: the `/demo` "Last demo calls" list used a non-unique React key (`label-message`) that
  collided on repeated identical calls — now keyed on a monotonic id
  (`apps/web-dashboard/components/demo-dashboard.tsx`) with a regression test.
- Severity: P2 / Product Decision
- Files:
  - `apps/web-dashboard/lib/demo-api.ts`
  - `apps/web-dashboard/app/api/demo/rfq-handoff/route.ts`
  - `apps/core-api/src/main/java/com/orderpilot/api/rest/DemoRfqHandoffController.java`
  - `apps/core-api/src/main/java/com/orderpilot/application/services/channel/LocalDemoRfqIntakeService.java`
- Root cause: the `/demo` button posts to `/api/v1/bot/telegram/webhook`, which creates the legacy
  bot RFQ/review records. The RFQ handoff workspace reads `channel_rfq_handoff`, which is created by
  the managed channel/bot bridge path. The two visible surfaces are not connected.
- Risk: a user can receive a successful “RFQ requires human review” response on `/demo`, then see no
  pending item in `/channels/rfq-handoffs`.
- Resolution: the browser now posts an empty request to a same-origin demo BFF. The BFF resolves the
  local demo tenant from server-only configuration; core resolves the operator, fixed demo Telegram
  connection, stable provider event identity, handoff state, deduplication, and audit, then delegates
  to the managed channel/bot bridge. The production Telegram webhook controller was not changed.
- Required proof/tests:
  - browser click on **Send demo Telegram RFQ** creates exactly one tenant-scoped handoff;
  - replay creates no duplicate event, bot RFQ, or handoff;
  - cross-tenant and spoofed source/connection attempts are denied;
  - no raw provider payload or internal connection ID is returned;
  - no quote approval, connector command, ChangeRequest, or outbox event is created.
- Proof:
  - `LocalDemoRfqIntakeServiceTest` proves one event, one bot RFQ, one `PENDING_REVIEW` handoff,
    stable replay, cross-tenant denial, and zero connector command/sandbox execution/ChangeRequest/
    outbox rows.
  - `DemoRfqHandoffControllerAuthorityBoundaryTest` proves `ADMIN_SETTINGS_MANAGE`, backend-owned
    actor resolution, spoofed body authority ignored, safe response fields, `STAFF_SUPPORT_READ`
    denial, and no service invocation on denial.
  - `DemoRfqHandoffControllerProductionGateTest.productionLikeRuntimeDeniesBeforeIntakeAndLeavesRfqTablesUntouched`
    proves a production-like profile is denied even when the endpoint flag is enabled, with no
    intake-service or inbound-event/bot-RFQ/channel-handoff repository interaction.
  - `apps/web-dashboard/tests/rfq-handoffs.test.mjs` proves the empty browser request, server-side
    tenant/permission injection, managed core path, bounded response contract, and redacted errors.
  - `RfqHandoffRealDemoPostgresIntegrationTest` contains the connection-only PostgreSQL creation and
    replay case; execution remains pending (`localhost:15432` refused the integration connection).
- Residual proof to close: DONE (2026-07-04, PR #242) — disposable PostgreSQL started, two-test
  integration command passed, live double-click browser walkthrough confirmed exactly one
  `PENDING_REVIEW` row and a safe terminal decision. See runbook section 14A.
- Owner/target week: Product/API owner; closed 2026-07-04.
- docs/api/quote-transactions.md:13
- commit b16b993db1c944555a356c7c77725292d6608dd6
- RuleID: generic-api-key
## P2 — Historical gitleaks finding in docs/api/quote-transactions.md

Severity:
P2 / Security Hygiene

Status:
OPEN / TRIAGE REQUIRED

Found by:
gitleaks git-history scan during proof/post-pr239-real-demo-proof.

Location:
docs/api/quote-transactions.md:13

Historical commit:
b16b993db1c944555a356c7c77725292d6608dd6

Finding:
generic-api-key pattern detected by gitleaks. Secret value is redacted in scan output.

Root cause:
Historical documentation example or committed token-like value requires classification.

Risk:
If this was a real API key or token, deleting it from current files is insufficient; the credential must be revoked/rotated. If it was a fake example, it should be rewritten to an obvious placeholder and optionally allowlisted only after review.

Suggested fix:

1. Inspect the historical/current line without exposing the value in chat.
2. Classify as real secret or fake/example.
3. If real: revoke/rotate immediately.
4. If fake: rewrite as `<example-api-key>` / `<redacted>` and document false-positive classification.
5. Consider a narrow `.gitleaksignore` only after confirming it is non-secret.

Required proof/tests:

- gitleaks current-tree scan clean.
- gitleaks history finding classified.
- No real secret present in current branch diff.

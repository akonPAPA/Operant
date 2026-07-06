# Fix Notebook

## P2 — Commerce Intelligence visible demo-flow read model (OP-CAP-27C)

- Status: PARTIAL (2026-07-05, PR #245) — the tenant-operator visible demo-flow read-model item is
  CLOSED. The endpoint and dashboard present tenant-scoped RFQ, AI advisory, review-required draft,
  blocker, safe-terminal, safety, and PR #244 runtime-posture facts without creating business state.
  Production analytics capabilities remain deferred below.
- Severity: P2 / Product Visibility and Data Boundary
- Path:
  - `GET /api/v1/commerce-intelligence/demo-flow` (`ANALYTICS_READ`)
  - `/commerce-intelligence`
- Root cause addressed: the guarded RFQ/AI/demo flow existed, but operators had to infer its product
  value across `/demo`, the RFQ handoff workspace, AI Work, draft quotes, and audit evidence.
- Risk closed: the visible projection is tenant-scoped, read-only, bounded, and uses a dedicated safe
  DTO. It does not expose authority/source/audit/idempotency/provider/runtime internals and does not
  infer production conversion, revenue, customer commitment, or measured external-row zeros.
- Safety decision: external writes / connector / outbox markers describe the existing demo
  contract. Connector-command, change-request, and outbox-execution row counts remain `NOT_MEASURED`
  rather than fake zero.
- Runtime decision: the response exposes only stable PR #244 posture labels. Runtime denial
  telemetry is `NOT_MEASURED`; no plan, quota bucket, Redis key, threshold, nonce/jti, retry-after,
  entitlement, or raw guard state is exposed.
- Residual / deferred items:
  - real analytics warehouse / OLAP and advanced KPI model;
  - production-grade bounded time-range filters and export;
  - real revenue/conversion/revenue-recognition metrics;
  - cross-channel non-demo intelligence;
  - a separate staff/support intelligence plane, if product policy later requires one;
  - live PostgreSQL/browser proof for the Commerce Intelligence route;
  - runtime denial telemetry dashboard and distributed runtime-guard telemetry;
  - provider-specific runtime accounting.
- Suggested future proof: disposable PostgreSQL tenant-isolation fixture plus browser verification of
  `/commerce-intelligence`, followed by dedicated telemetry design before any denial-rate surface.
- Owner/target week: Product/API and runtime owners; unscheduled.

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

## PG-248-01 — Support-plane audit writes fail on real PostgreSQL (staff actor violates audit FK)

- Status: **RESOLVED IN PR #248** (2026-07-06). Product/architecture decision taken: `audit_event.actor_id`
  is an opaque/polymorphic principal id, not an FK to `user_account`. Fixed by
  `V66__audit_event_actor_id_polymorphic_principal.sql` (drops the FK, documents the column). Positively
  proven on real PostgreSQL by `SupportGrantPersistencePostgresIntegrationTest`
  (`supportServiceAuditWritesPersistForStaffActorsUnderPostgres`,
  `auditActorIdFkToUserAccountIsDroppedAndAcceptsStaffAndTenantActors`) — allow AND deny support audits now
  persist with a `staff_user` actor, tenant-user audit still works, and the FK is proven dropped. Follow-up
  hardening tracked as PG-248-02 (below).
- Severity: **P1 / Data & Audit Integrity (production-blocking on PostgreSQL, H2-hidden)** — now resolved.
- Path:
  - `apps/core-api/src/main/resources/db/migration/V1__platform_foundation.sql:55`
    (`audit_event.actor_id UUID NULL REFERENCES user_account(id)`)
  - `apps/core-api/src/main/java/com/orderpilot/application/services/AuditEventService.java`
    (`record(...)` inserts `actor_id` = caller-supplied actor)
  - `apps/core-api/src/main/java/com/orderpilot/api/rest/InternalSupportController.java`
    (every support action passes `actor = staffIdentityResolver.resolveRequired(http).staffUserId()`)
  - `apps/core-api/src/main/java/com/orderpilot/application/services/support/SupportAccessService.java`
    (`authorize` / `createGrant` / `approveGrant` / `rejectGrant` / `revokeGrant` audit with the staff id)
- Root cause: `audit_event.actor_id` has a Flyway foreign key to `user_account(id)`. The Operant support
  plane is a **separate identity domain**: the acting principal is a `staff_user` id (resolved by the
  `StaffIdentityResolver` seam), which is by design NOT a `user_account` row. Every support-plane audit
  write therefore inserts an `actor_id` that is absent from `user_account` and fails
  `audit_event_actor_id_fkey` (SQLSTATE 23503) on real PostgreSQL. The audit is written via
  `@Transactional(REQUIRES_NEW)`, so the violation propagates as a `DataIntegrityViolationException` and
  aborts the whole support action.
- Why H2 hides it: the H2 test profile (`application-test.yml`) sets `spring.flyway.enabled: false` and
  `jpa.hibernate.ddl-auto: create-drop`, so the schema is generated from JPA entities. `AuditEvent.actorId`
  is mapped as a plain `UUID` column with no `@ManyToOne`/association, so H2 never creates the FK. All
  support/incident unit + service tests are green on H2 while the real Postgres schema rejects the write.
- Risk:
  - The entire owner-company support & maintenance plane (OP-CAP-51..54: grant create/approve/reject/
    revoke, `authorize`, diagnostics, maintenance records, data-repair dry-run/approval, processing-job
    repair) is non-functional on PostgreSQL — actions fail and roll back.
  - **Security-relevant:** even the fail-closed DENY audit (`SUPPORT_ACCESS_DENIED`) cannot be written,
    so support-access denials are unauditable on PostgreSQL.
  - `InternalIncidentController` is NOT affected: it audits via the tenant `RequestActorResolver`
    (`resolveVerifiedActor`), which yields a `user_account` id.
- Suggested fix (needs owner decision — pick one, do not guess in a proof PR):
  1. Drop the `audit_event.actor_id → user_account(id)` FK (audit is an append-only provenance record;
     `actor_id` is already nullable and non-authoritative; a unified principal namespace is not modeled).
     Additive `ALTER TABLE audit_event DROP CONSTRAINT audit_event_actor_id_fkey`; OR
  2. Add a dedicated `staff_actor_id`/`actor_kind` discriminator so staff-plane audits are stored without
     colliding with the tenant `user_account` FK; OR
  3. Record support-plane audits with `actor_id = NULL` and the staff id in safe metadata (weakens
     first-class actor attribution — least preferred).
- Required proof/tests:
  - New Postgres integration test showing `SupportAccessService.authorize` (allow) returns a session and
    persists a `SUPPORT_ACCESS_GRANTED` audit, and that a missing grant throws `SupportAccessDeniedException`
    and persists a `SUPPORT_ACCESS_DENIED` audit.
  - Flip `SupportGrantPersistencePostgresIntegrationTest
    .supportServiceAuditWritesFailOnPostgresBecauseStaffActorViolatesAuditFk_DEFECT` from a defect
    characterization to the positive allow/deny assertions above.
  - Re-run the full `com.orderpilot.integration.testdb.*` Postgres suite.
- Owner/target week: Core API / audit-boundary owner; unscheduled.
- Resolution in PR #248: the audit referential-integrity decision was taken and applied within #248 — the
  overly-narrow FK was dropped (V66) and `actor_id` is now an opaque/polymorphic principal id. The fix has
  minimal blast radius (one additive migration; no Java/main logic change) and is positively proven on real
  PostgreSQL. Broader first-class actor-type provenance is the only piece intentionally deferred (PG-248-02).

## PG-248-02 — audit_event lacks an explicit actor principal type/source (forensic hardening)

- Status: OPEN (2026-07-06, opened by PR #248 as the follow-up to PG-248-01). **Non-blocking / P2.**
- Severity: P2 / Audit Forensics & Reporting
- Path:
  - `apps/core-api/src/main/resources/db/migration/V66__audit_event_actor_id_polymorphic_principal.sql`
    (`actor_id` is now opaque/polymorphic)
  - `apps/core-api/src/main/java/com/orderpilot/domain/audit/AuditEvent.java`
  - `apps/core-api/src/main/java/com/orderpilot/application/services/AuditEventService.java` (and callers)
- Root cause: after PG-248-01, `audit_event.actor_id` is correctly polymorphic/opaque, but the actor's
  identity domain is not encoded as a first-class column. A reader cannot tell from the row alone whether an
  actor is a tenant user, an Operant staff user, a service account, a connector/bot/worker, or a
  system/runtime job.
- Risk: forensic/reporting ambiguity across identity domains; harder cross-domain audit queries; weaker
  attribution if two domains ever mint colliding UUID namespaces (unlikely with random UUIDs, but not
  guaranteed by the schema).
- Suggested fix: add `actor_principal_type` (enum: `TENANT_USER`/`STAFF_USER`/`SERVICE_ACCOUNT`/`CONNECTOR`/
  `SYSTEM`) and/or `actor_source` in a bounded future migration; set it in `AuditEventService` from the
  resolving seam (`RequestActorResolver` → `TENANT_USER`, `StaffIdentityResolver` → `STAFF_USER`, etc.).
  Keep it additive/nullable-with-default; do not rewrite existing rows.
- Required proof/tests: a Postgres test asserting tenant-user, staff, system, and service-account audit rows
  carry the correct `actor_principal_type`; existing audit read/query paths unaffected.
- Owner/target week: Core API / audit-boundary owner; unscheduled.
- Why not in PR #248: #248 fixed the production-blocking FK defect (PG-248-01) with minimal blast radius.
  Adding a first-class actor-type column touches the audit write path and all audit callers — a separate,
  bounded slice, not required to unblock the support plane on PostgreSQL.

# Fix Notebook

## P3 — Operator Cockpit v1 guided RFQ-to-quote: deferred proof + draft-quote detail route (GitHub PR #260 / Operator Cockpit v1 slice)

- Status: OPEN (2026-07-07, GitHub PR #260 / Operator Cockpit v1 slice). **P3 / Product Visibility (non-blocking).**
- Severity: P3 / Frontend Product Polish and Proof
- Path:
  - `apps/web-dashboard/components/rfq-cockpit-journey.tsx` (guided read/display-only journey panel)
  - `apps/web-dashboard/components/rfq-handoff-workspace.tsx` (renders the journey above the raw detail)
  - `apps/web-dashboard/tests/rfq-cockpit-journey.test.mjs`
  - `docs/runbooks/operator-cockpit-guided-rfq-to-quote.md`
- What landed: a coherent guided cockpit that shows source channel, detected intent, request text, handoff
  status, AI advisory status, draft quote status, draft line count, safe terminal state, audit status, and
  an explicit next-operator-action, plus honest `NOT_GENERATED`/`NOT_CREATED`/`NOT_RECORDED`/`NOT_MEASURED`
  state tokens, the `externalExecution=DISABLED` safety posture, and links to `/commerce-intelligence` and
  `/runtime-control`. Frontend-only; reuses the existing backend contracts unchanged.
- Deferred item 1 — **live PostgreSQL + real-browser screenshot proof** of the guided cockpit journey was
  not executed in this environment. Source-inspection tests prove the source contract and forbidden-field
  absence (consistent with the existing `rfq-handoffs.test.mjs` convention), and the full frontend suite
  passes; live browser rendering remains deferred. Suggested proof: seed one
  handoff against a disposable PostgreSQL demo DB and capture the `/channels/rfq-handoffs` walkthrough to
  `SAFE_DEMO_TERMINAL` with the cockpit visible.
- Deferred item 2 — a **first-class draft-quote inspection route/detail view** from the cockpit. This slice
  intentionally did not invent a new backend endpoint or a fragile draft-quote mutation. The cockpit shows
  the draft summary the existing `create-draft-quote` response already returns. A dedicated draft-quote
  detail navigation/inspection surface is deferred to a **follow-up live operator transition proof slice**
  and must reuse an existing backend contract (no parallel business model, no new external-write path).
- Risk: low — read/display-only; no backend, migration, or contract change; no external execution added.
- Owner/target week: Product/Frontend owner; follow-up live operator transition proof slice (draft-quote inspection) unscheduled.

## P2 — Runtime-control telemetry dashboard for the RFQ/AI/demo path (OP-CAP-27D)

- Status: **PARTIALLY CLOSED for the RFQ/AI/demo path only** (2026-07-06, PR #252). The operator-facing
  runtime-control telemetry read model + dashboard is CLOSED for the RFQ/AI/demo path; production
  distributed telemetry and denial-rate surfaces remain deferred (below). This closes the
  "runtime denial telemetry dashboard" item **only** for the demo path — it does not open a production
  telemetry plane.
- Update (2026-07-06, PR #253) — three post-merge follow-ups CLOSED: (1) **route hardening** — non-GET
  `/api/v1/runtime-control/**` is now fail-closed/default-denied and can no longer inherit the generic
  `/api/v1/runtime` → `RUNTIME_ENTITLEMENT_MANAGE` write rule (`ApiRouteSecurityPolicy` carve-out ordered
  before the generic runtime branch; proven by a dedicated policy test + controller POST tests asserting
  zero service interaction). (2) **wording honesty** — the surface is now stated as a tenant-*gated* read
  of the *default/static* runtime-control contract posture, **not** tenant-specific quota/rate/entitlement
  or production denial-rate telemetry; new `NOT_MEASURED` codes `TENANT_SPECIFIC_RUNTIME_POLICY_NOT_MEASURED`
  / `TENANT_RATE_BUCKET_STATE_NOT_MEASURED` / `TENANT_QUOTA_BUCKET_STATE_NOT_MEASURED` /
  `RUNTIME_ADMISSION_DENIAL_COUNTERS_NOT_MEASURED` make the missing tenant-specific dimensions explicit.
  (3) **frontend client hardening** — malformed JSON and response-contract drift now map to bounded
  "response is invalid" / "contract is invalid" messages (minimal local type guards, no new dependency);
  a raw backend body is never echoed. This does **not** add tenant-specific telemetry, persisted counters,
  distributed telemetry, staff/support plane, or any write surface.
- Severity: P2 / Runtime Visibility and Data Boundary
- Path:
  - `GET /api/v1/runtime-control/demo-flow` (`ANALYTICS_READ`)
  - `/runtime-control` (nav: Intelligence → Runtime Control Telemetry)
  - `apps/core-api/src/main/java/com/orderpilot/api/dto/RuntimeControlTelemetryDtos.java`
  - `apps/core-api/src/main/java/com/orderpilot/application/services/runtime/RuntimeControlDemoFlowTelemetryService.java`
  - `apps/core-api/src/main/java/com/orderpilot/api/rest/RuntimeControlTelemetryController.java`
  - `apps/core-api/src/main/java/com/orderpilot/security/ApiRouteSecurityPolicy.java` (new
    `/api/v1/runtime-control` rule ordered before `/api/v1/runtime`)
  - `apps/web-dashboard/lib/runtime-control-telemetry-api.ts`,
    `apps/web-dashboard/components/runtime-control-telemetry-panel.tsx`,
    `apps/web-dashboard/app/(dashboard)/runtime-control/page.tsx`
  - `docs/runbooks/runtime-control-telemetry-demo-flow.md`
- Root cause addressed: PR #244 guards the RFQ/AI/demo checkpoints, but the runtime-control posture was
  invisible — operators could not tell whether the demo flow was gated (workload type, sync/async, cheap
  vs AI path, quota/rate/backpressure posture) or what was measured vs. not.
- Risk closed: the projection is tenant-scoped, read-only, bounded, and uses a dedicated safe DTO. It
  exposes only stable posture tokens and `STATIC_CONTRACT` thresholds from `RuntimeControlProperties`; it
  never invokes the guard, calls a connector, performs an external write, or exposes tenant/actor/source/
  audit/idempotency/provider/plan/quota-bucket/rate-window/raw-guard internals.
- Honesty decision: every metric self-labels `MEASURED` / `STATIC_CONTRACT` / `NOT_MEASURED` /
  `NOT_APPLICABLE`. Admission/denial counts are `NOT_MEASURED` (null value, never a fake zero) because
  runtime-control admission is deterministic and records no counters.
- Tests: `RuntimeControlTelemetryControllerTest` (4 — ANALYTICS_READ allowed; missing → 401 before
  service; wrong/unrelated/`RUNTIME_ENTITLEMENT_READ`/`STAFF_SUPPORT_READ` → 403 before service;
  client-supplied tenant/actor/source/status/runtime query+body ignored), `RuntimeControlDemoFlowTelemetryServiceTest`
  (3 — tenant-scoped static posture + NOT_MEASURED counters; disabled-contract honesty; missing-tenant
  fails closed), `ApiRouteSecurityClassificationTest` (+1 route expectation → 41), `ResponseDtoLeakContractTest`
  (7, still green — new controller DTO scanned), `ControllerEntityReturnBanTest` (1), plus frontend
  `runtime-control-telemetry.render.test.mjs` (7 render states) and unchanged
  `commerce-intelligence.render.test.mjs` (6, still green).
- Residual / deferred items (NOT proven by this slice):
  - real production distributed runtime telemetry (multi-node runtime-guard behaviour);
  - provider-specific runtime billing / accounting;
  - runtime denial telemetry for all channels (only the RFQ/AI/demo path is described);
  - a support/staff runtime telemetry plane;
  - OLAP / warehouse metrics, export, or time-range scope;
  - production SSO/auth;
  - real ERP/1C/connector execution;
  - live PostgreSQL + real-browser screenshot proof (render-state proof landed here; live-DB browser run
    still deferred, as with the Commerce Intelligence route).
- Owner/target week: Runtime/platform + Product/API owners; unscheduled.

## GH-249-01 — Admin role can bypass the `main` ruleset `always`

- Status: **PARTIALLY RESOLVED / RISK REDUCED IN STAGE 30B** (2026-07-06); residual (full removal)
  remains **OPEN**. **P1.**
- Severity: P1 / Repository Governance
- Path / setting: repository ruleset `akonya tigr` (id `17327601`) →
  `bypass_actors: [{ actor_type: RepositoryRole, actor_id: 5 (Admin), bypass_mode: always }]` →
  **`bypass_mode: pull_request`**; `current_user_can_bypass: always` → **`pull_requests_only`**.
- Evidence: `gh api repos/akonPAPA/Operant/rulesets/17327601`. After-state re-read confirms
  `bypass_actors=[{RepositoryRole,5,pull_request}]`, `current_user_can_bypass=pull_requests_only`
  (see `docs/security/github-repository-settings-proof.md` → Stage 30B after-state).
- Root cause: the ruleset granted the Admin repository role unconditional (`always`) bypass of
  required review, required checks, signed commits, linear history, and force-push/deletion rules.
- Risk (before): any admin could merge unreviewed/check-failing code into `main` — including via a
  **direct push** to `main` — defeating the entire protection baseline.
- Fix applied: restricted admin bypass from `always` to `pull_request`, so the Admin role can no longer
  bypass the ruleset on a direct push; admin changes must go through a pull request.
- Residual (still OPEN): `pull_request` bypass mode still allows an admin to merge a PR that does not
  meet the review/status requirements. **Full removal is not currently safe**: required approvals = 1 +
  code-owner review with **no second reviewer** on the repo means a PR author cannot self-approve, so
  removing bypass entirely would make every PR unmergeable with no recovery path (hard stop condition).
- Suggested completion: add a second approver / reviewer team, then remove the Admin bypass actor (or
  narrow further) so admins are fully subject to required review + strict checks; re-read ruleset and
  confirm a test PR is blocked until review + checks pass.
- Owner/target week: Repo owner / DevSecOps; partial fix done 2026-07-06 (Stage 30B); full removal
  contingent on a second reviewer.

## GH-249-02 — Stale reviews are not dismissed on new pushes to `main` PRs

- Status: **RESOLVED IN STAGE 30B** (2026-07-06). Enabled `dismiss_stale_reviews_on_push` on ruleset
  `17327601`. **P1.**
- Severity: P1 / Repository Governance
- Path / setting: ruleset `17327601` → `pull_request.dismiss_stale_reviews_on_push: false` → **`true`**.
- Evidence: `gh api repos/akonPAPA/Operant/rulesets/17327601`. After-state re-read confirms
  `dismiss_stale_reviews_on_push: true` (see
  `docs/security/github-repository-settings-proof.md` → Stage 30B after-state).
- Root cause: an approval remained valid after further commits were pushed to the PR head.
- Risk: reviewer approves version A; author (or admin) pushes version B and merges without re-review —
  a classic approve-then-change bypass, especially dangerous on large PRs.
- Fix applied: enabled `Dismiss stale pull request approvals when new commits are pushed` via
  `gh api --method PUT repos/akonPAPA/Operant/rulesets/17327601`.
- Proof: after-state ruleset re-read shows `dismiss_stale_reviews_on_push: true`.
- Residual: not yet *exercised* against a real second-reviewer flow (repo is single-maintainer); the
  control is correctly configured but cannot be behaviourally demonstrated until a second approver exists.
- Owner/target week: Repo owner / DevSecOps; done 2026-07-06 (Stage 30B).

## GH-249-03 — Last-push / self-approval guard disabled on `main`

- Status: OPEN (2026-07-06, PR #249). **P2.**
- Severity: P2 / Repository Governance
- Path / setting: ruleset `17327601` → `pull_request.require_last_push_approval: false`.
- Evidence: `gh api repos/akonPAPA/Operant/rulesets/17327601`.
- Root cause: the most recent push does not require approval from someone other than its pusher, so an
  author who is also a reviewer can effectively self-approve their latest change.
- Risk: single-actor merge path; weakens the "human approves if risky" boundary.
- Suggested fix: enable `Require approval of the most recent reviewable push`.
- Required proof/tests: re-read ruleset; confirm `require_last_push_approval: true`.
- Owner/target week: Repo owner / DevSecOps; unscheduled.
- Why not fixed in PR #249: proof/documentation PR; no setting changes without separate authorization.
- Stage 30B decision: reviewed and **deferred**. Last-push approval requires approval from someone other
  than the pusher; with the current single-maintainer (solo-founder) workflow and no second reviewer,
  enabling it would block all merges with no recovery path. Contingent on adding a second approver.
  Ruleset re-read confirms `require_last_push_approval: false` (unchanged).

## GH-249-04 — Conversation resolution not required before merge

- Status: **RESOLVED IN STAGE 30B** (2026-07-06). Enabled `required_review_thread_resolution` on ruleset
  `17327601`. **P2.**
- Severity: P2 / Repository Governance
- Path / setting: ruleset `17327601` → `pull_request.required_review_thread_resolution: false` → **`true`**.
- Evidence: `gh api repos/akonPAPA/Operant/rulesets/17327601`. After-state re-read confirms
  `required_review_thread_resolution: true` (see
  `docs/security/github-repository-settings-proof.md` → Stage 30B after-state).
- Root cause: open review threads did not block merge.
- Risk: unresolved reviewer objections can be merged over, losing security/correctness feedback.
- Fix applied: enabled `Require conversation resolution before merging` via
  `gh api --method PUT repos/akonPAPA/Operant/rulesets/17327601`.
- Proof: after-state ruleset re-read shows `required_review_thread_resolution: true`.
- Owner/target week: Repo owner / DevSecOps; done 2026-07-06 (Stage 30B).

## GH-249-05 — Frontend and AI Worker checks are not required on `main`

- Status: OPEN (2026-07-06, PR #249). **P2.**
- Severity: P2 / CI Gating
- Path / setting: ruleset `17327601` required checks = `Backend tests`, `Docker Compose config`,
  `CodeQL` only. `.github/workflows/frontend.yml` (Frontend) and `.github/workflows/ai-worker.yml`
  (AI Worker) run on PR but are path-filtered and not required.
- Evidence: ruleset required_status_checks vs `#248` check-runs; workflow triggers.
- Root cause: only backend/compose/CodeQL are enforced; frontend/worker regressions can merge when
  their path-filtered checks are skipped or fail-without-blocking.
- Risk: broken web-dashboard or ai-worker merged to `main` undetected by required gate.
- Suggested fix: add `Frontend` and `AI Worker` to required checks using skip-safe status reporting
  (e.g. a `changes`-gated required job that reports success when its paths are untouched) so docs-only
  PRs are not deadlocked.
- Required proof/tests: PR touching only backend + docs still mergeable; PR breaking frontend blocked.
- Owner/target week: CI owner; unscheduled.
- Why not fixed in PR #249: proof/documentation PR; no setting/CI changes without separate authorization.
- Stage 30B decision: reviewed and **deferred**. `frontend.yml` and `ai-worker.yml` gate their real
  build jobs behind a `changes` path filter, so the meaningful checks are conditional/skipped on
  unrelated PRs. Requiring a non-skip-safe context would leave docs-only PRs waiting on a status that
  never reports (merge deadlock). A skip-safe required job requires a **workflow YAML edit**, which is
  outside Stage 30B's allowed paths (settings + docs only). Deferred to a CI-scoped stage.

## GH-249-06 — `merge` commit method allowed while linear history is required

- Status: OPEN (2026-07-06, PR #249). **P3.**
- Severity: P3 / Policy Consistency
- Path / setting: ruleset `17327601` → `pull_request.allowed_merge_methods: [merge, squash, rebase]`
  while `required_linear_history: true`; repo default merge method is `SQUASH`.
- Evidence: `gh api repos/akonPAPA/Operant/rulesets/17327601`, `gh repo view --json viewerDefaultMergeMethod`.
- Root cause: a true merge commit would be rejected by linear-history enforcement, so offering it is
  misleading.
- Risk: operator confusion / failed merge attempts; no security impact.
- Suggested fix: drop `merge` from `allowed_merge_methods`, leaving `squash`/`rebase`.
- Required proof/tests: re-read ruleset; confirm `merge` removed.
- Owner/target week: Repo owner; unscheduled.
- Why not fixed in PR #249: proof/documentation PR; no setting changes without separate authorization.
- Stage 30B decision: reviewed; **left unchanged** (P3 consistency only, no security impact). After-state
  re-read still shows `allowed_merge_methods: [merge, squash, rebase]`. Kept OPEN.

## GH-249-07 — Semgrep runs and passes but is not a required check

- Status: OPEN (2026-07-06, PR #249). **P1.**
- Severity: P1 / Security Gating
- Path / setting: `.github/workflows/semgrep.yml` ("Semgrep Security Scan", check
  `Semgrep SAST / OP policy scan") runs on every PR and passed on `#248`, but is absent from ruleset
  `17327601` required checks.
- Evidence: `#248` check-runs show `Semgrep SAST / OP policy scan = success`; ruleset required checks
  list only `Backend tests`, `Docker Compose config`, `CodeQL`.
- Root cause: the SAST gate is advisory — a failing Semgrep scan does not block merge.
- Risk: security findings in the blocking Semgrep policy (`.semgrep/op-security-blocking.yml`) can be
  merged past, undermining the "Rules validate" safety layer.
- Suggested fix: add `Semgrep SAST / OP policy scan` to the ruleset's required status checks (strict).
- Required proof/tests: re-read ruleset; confirm Semgrep context present; PR with a seeded blocking
  finding is blocked from merge.
- Owner/target week: DevSecOps / CI owner; before next high-risk merge.
- Why not fixed in PR #249: proof/documentation PR; no setting changes without separate authorization.
- Stage 30B decision: reviewed and **deferred** (evidence-backed). `.github/workflows/semgrep.yml` is
  **path-filtered** to `apps/**`, `infra/**`, `scripts/**`, `.github/workflows/**`, `.semgrep/**`, so the
  `Semgrep SAST / OP policy scan` context does not run on docs-only PRs. Confirmed empirically: the check
  was present on code PRs #247 and #248 but **absent** on docs-only PR #249. Making it a required check
  now would leave docs-only PRs (including this Stage 30B PR) waiting on a status that never reports —
  a merge deadlock. Making it skip-safe requires a **workflow YAML edit**, outside Stage 30B's allowed
  paths. Deferred to a CI-scoped stage that can add a skip-safe required status. Kept OPEN.

## GH-249-08 — Snyk runs only on schedule/dispatch, not on PRs, and is not required

- Status: OPEN (2026-07-06, PR #249). **P2.**
- Severity: P2 / Dependency Scanning
- Path / setting: `.github/workflows/snyk.yml` triggers = `schedule` + `workflow_dispatch` only (no
  `pull_request`); not in ruleset required checks.
- Evidence: `grep -nE '^on:|schedule|pull_request' .github/workflows/snyk.yml`; ruleset required checks.
- Root cause: dependency vulnerability scanning does not run per-PR and does not gate merges; only a
  periodic/manual sweep exists.
- Risk: a PR introducing vulnerable dependencies (`pom.xml`, `web-dashboard` npm) merges without a Snyk
  gate; the gap is only caught on the next scheduled run.
- Suggested fix: add `pull_request` trigger to `snyk.yml` (with sensible severity threshold) and add its
  check to the ruleset required checks. (Dependabot alerts already enabled provide partial coverage.)
- Required proof/tests: PR adding a known-vulnerable dep blocked; re-read ruleset for Snyk context.
- Owner/target week: DevSecOps / CI owner; unscheduled.
- Why not fixed in PR #249: proof/documentation PR; no setting/CI changes without separate authorization.
- Stage 30B decision: reviewed and **deferred**. `snyk.yml` triggers are `schedule` + `workflow_dispatch`
  only (no `pull_request`), so a Snyk check never reports on a PR; requiring it would deadlock all PRs.
  Adding a `pull_request` trigger + documented fail policy is a **workflow YAML edit**, outside Stage 30B's
  allowed paths. Kept OPEN until Snyk reliably runs on PRs with a documented fail policy.

## GH-249-09 — Secret-scanning non-provider patterns and validity checks disabled

- Status: OPEN (2026-07-06, PR #249). **P3.**
- Severity: P3 / Hardening
- Path / setting: repo `security_and_analysis` →
  `secret_scanning_non_provider_patterns: disabled`, `secret_scanning_validity_checks: disabled`
  (base secret scanning + push protection are enabled; 0 open alerts).
- Evidence: `gh api repos/akonPAPA/Operant --jq '.security_and_analysis'`.
- Root cause: only provider-recognized secret patterns are scanned, and detected secrets are not
  validity-checked (active vs revoked).
- Risk: custom/non-provider secret formats may go undetected; alerts lack active/inactive context.
- Suggested fix: enable non-provider pattern scanning and validity checks in Code security settings.
- Required proof/tests: re-read `security_and_analysis`; confirm both `enabled`.
- Owner/target week: DevSecOps; unscheduled.
- Why not fixed in PR #249: proof/documentation PR; no setting changes without separate authorization.

## GH-249-10 — 386 open Codacy code-scanning alerts (code-quality hygiene)

- Status: OPEN (2026-07-06, PR #249). **P3.**
- Severity: P3 / Hygiene
- Path / setting: code scanning alerts — 386 open, 362 `Pylint (Codacy)`, 24 `Remark-lint (Codacy)`;
  0 CodeQL, 0 with a security-severity level.
- Evidence: `gh api "repos/akonPAPA/Operant/code-scanning/alerts?state=open" --paginate` (counts only).
- Root cause: Codacy code-quality tooling reports a large volume of non-security alerts that are not
  being triaged.
- Risk: no direct security impact (no security severity), but the volume can mask a future real finding
  and dilutes the code-scanning dashboard.
- Suggested fix: triage/dismiss or tune the Codacy Pylint/Remark-lint rulesets; keep security-severity
  alerts (CodeQL/Bandit) as the enforced gate.
- Required proof/tests: open-alert count reduced; CodeQL security alerts remain at 0.
- Owner/target week: Backend / tooling owner; unscheduled.
- Why not fixed in PR #249: proof/documentation PR; alert triage is out of scope and unauthorized here.

## P2 — Commerce Intelligence visible demo-flow read model (OP-CAP-27C)

- Status: PARTIAL (2026-07-05, PR #245) — the tenant-operator visible demo-flow read-model item is
  CLOSED. The endpoint and dashboard present tenant-scoped RFQ, AI advisory, review-required draft,
  blocker, safe-terminal, safety, and PR #244 runtime-posture facts without creating business state.
  Production analytics capabilities remain deferred below.
- Update (2026-07-06, PR #251) — rendered-page browser verification is now proven at the render level.
  A real-component render-state proof (`apps/web-dashboard/tests/commerce-intelligence.render.test.mjs`,
  6/6, react-dom/server) proves the populated, empty, unavailable, backend-error, and partial-error
  states render safely with no internal/raw field leak, no raw JSON/`<pre>` dump, and no backend
  stack/exception string; a backend test now proves the read endpoint ignores client-supplied
  tenant/actor/source/status/runtime authority and body. A browser walkthrough runbook was added
  (`docs/runbooks/commerce-intelligence-browser-proof.md`). This closes the PR #245 "rendered-page
  browser verification" gap. **Live PostgreSQL + real-browser screenshot proof was not executed in
  this environment and remains deferred** (see residual items). No production analytics scope added;
  production is not complete.
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
  - live PostgreSQL + real-browser screenshot proof for the Commerce Intelligence route (render-state
    browser proof and a documented walkthrough landed in PR #251; live-DB browser run still deferred);
  - runtime denial telemetry dashboard and distributed runtime-guard telemetry (a read-only
    runtime-control **posture** telemetry surface for the RFQ/AI/demo path landed in PR #252 with
    denial/admission counts labelled `NOT_MEASURED`; production denial-rate + distributed telemetry
    remain deferred — see the OP-CAP-27D entry above);
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
    low — a safe read-only runtime-control **posture** surface for the RFQ/AI/demo path landed in
    PR #252 (OP-CAP-27D); global taxonomy + quota dashboards remain deferred and must read only safe
    runtime evidence.
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
- Resolution in PR #248:
  Dropped `audit_event_actor_id_fkey` through
  `V66__audit_event_actor_id_polymorphic_principal.sql`.
  `audit_event.actor_id` is now documented as an opaque/polymorphic principal id.
  The column, existing data, and audit indexes are preserved.

- Proof:
  `SupportGrantPersistencePostgresIntegrationTest` proves:
  - the FK is absent;
  - support allow audit persists with `staff_user` actor;
  - support deny audit persists with `staff_user` actor;
  - tenant-user audit still persists;
  - support audit remains tenant-scoped;
  - denied support access does not create maintenance/data-repair side effects.

- Residual:
  `actor_principal_type` / `actor_source` remains deferred as PG-248-02.
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

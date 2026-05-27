Title: Stage 7-10 cumulative hardening: bot/integration safety, Stage 9B execution controls, Stage 10 demo readiness

## Summary

This is a cumulative Stage 7-10 branch. Prior accepted Stage 7-9 work was staged and uncommitted when Stage 10 verification and hardening were completed, so the pushed commit contains the verified current tree rather than a small Stage 10-only docs change.

## Major changes

### Backend safety

- Preserves bot-only handoffs as operator handoff markers, not validation-backed cases.
- Adds commerce analytics, reconciliation, business value metrics, and ROI assumptions over tenant-scoped existing records.
- Adds Stage 9 integration control around `ChangeRequest`, Demo ERP execution, sync/audit visibility, retry/cancel metadata, and external reference tracking.
- Hardens Stage 9B idempotency and execution policy with demo-only adapter execution.

### Frontend contract/UI

- Adds command-center, reconciliation, business-value, validation-review, bot handoff, and integration-control UI surfaces.
- Preserves `connectorIdempotencyKeyHash` as the frontend/API contract field.
- Shows demo-only execution mode, credential placeholder status, retry labels, masked idempotency display, and audit timeline evidence.

### Security docs/runbooks

- Adds or updates threat model, security checklist, AI/bot boundaries, connector credential boundary, connector safety model, incident response, connector failure runbook, and UAT checklist.
- Documents sandbox Maven dependency-resolution blocker and approved full-suite backend verification.

### Investor demo/UAT docs

- Adds concise and technical demo scripts plus demo scenarios for intake, validation, analytics, Demo ERP ChangeRequest execution, replay, policy block, failure visibility, and audit evidence.
- Adds UAT checklist for local app startup, tests, tenant isolation, replay, policy block, audit timeline, masked idempotency hash, placeholder credentials, and disabled production connectors.

### Verification

- Backend full suite passed with approved dependency/network access.
- Frontend lint, tests, and build passed.
- AI worker tests passed through the repo virtual environment.

## Stage 9B safety guarantees

- Execution remains `DEMO_ONLY`.
- Approved validation-backed `ChangeRequest` remains the lifecycle source.
- Duplicate execute on the same approved ChangeRequest returns the same external reference and hash.
- Replay audit records include `idempotencyKeyHash`, `replay:true`, and `networkCall:false`.
- Attempt/success/failure audit records include `executionMode: DEMO_ONLY`.
- Non-demo targets are policy-blocked with `CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED`.
- Tenant B cannot execute tenant A ChangeRequest.
- Stage 9B demo execution does not create `ConnectorCommand` records.
- Bot-only handoffs cannot create connector execution.

## Verification

- `cd C:\OrderPilot\OrderPilot-Core\apps\core-api; mvn test` - PASS, 312 tests, 0 failures, 0 errors, 0 skipped, when run with approved dependency/network access.
- `cd C:\OrderPilot\OrderPilot-Core\apps\core-api; mvn "-Dtest=Stage9SecurityDocumentationTest" test` - PASS, 2 tests.
- `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm.cmd run lint` - PASS.
- `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm.cmd run test` - PASS, 39 tests.
- `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm.cmd run build` - PASS.
- `cd C:\OrderPilot\OrderPilot-Core\apps\ai-worker; .\.venv\Scripts\python.exe -m pytest` - PASS, 12 tests.

## Risks / follow-ups

- `connector_idempotency_key` database column rename is deferred; it stores `sha256:*` hash values only and is exposed as `connectorIdempotencyKeyHash`.
- Sandboxed Maven remains blocked by dependency/network restrictions: `Permission denied: getsockopt`.
- Production connector activation remains future work and requires separate security/runbook acceptance.
- Secret-scan noise reviewed: examples/dev placeholders only; no ignored local secrets staged.
- Local `sample.csv` remains untracked and outside the PR.

## Explicitly disabled

- Real ERP/1C writes.
- External network calls from connector execution.
- Raw secrets.
- Inventory mutation through Stage 9 integration paths.
- Bot-triggered connector commands.

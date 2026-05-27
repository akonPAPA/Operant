# PR Stage 7-10 Review Notes

## Why this PR is cumulative

This branch is cumulative because prior accepted Stage 7-9 work was still staged and uncommitted when Stage 10 verification and hardening were completed. The Stage 10 commit therefore includes the verified current tree instead of a small docs-only delta. Treat this as the reviewed Stage 7-10 integration branch.

## Included areas

- Stage 7 bot/runtime work: controlled bot runtime, Telegram-style inbound handling, response drafts, operator handoff, and bot-only review boundary hardening.
- Stage 8 commerce and reconciliation work: command-center analytics, business value metrics, ROI assumptions, inventory reconciliation summary/cases/timeline, and read-model query hardening.
- Stage 9 integration control: tenant-scoped integration facade, Demo ERP connection, ChangeRequest lifecycle, sync/audit visibility, and external reference tracking.
- Stage 9B ChangeRequest execution safety: demo-only policy, idempotency hash contract, replay handling, retry/cancel metadata, credential placeholder boundary, and connector audit evidence.
- Stage 10 security/reliability/demo hardening: security evidence pack, runbooks, UAT checklist, investor demo scripts, verification notes, and PR review material.
- Existing cumulative branch diff also contains previously staged channel/connector foundation files and docs. Review them as part of the cumulative branch; they are not Stage 10 product scope expansion.

## Verification

- `cd C:\OrderPilot\OrderPilot-Core\apps\core-api; mvn test` - PASS, 312 tests, 0 failures, 0 errors, 0 skipped, when run with approved dependency/network access.
- `cd C:\OrderPilot\OrderPilot-Core\apps\core-api; mvn "-Dtest=Stage9SecurityDocumentationTest" test` - PASS, 2 tests.
- `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm.cmd run lint` - PASS.
- `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm.cmd run test` - PASS, 39 tests.
- `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm.cmd run build` - PASS.
- `cd C:\OrderPilot\OrderPilot-Core\apps\ai-worker; .\.venv\Scripts\python.exe -m pytest` - PASS, 12 tests. System `python` and `py` were not available on PATH.

## Known risks

- Sandboxed Maven dependency resolution remains blocked by network restrictions: `Permission denied: getsockopt` while resolving the Spring Boot parent POM.
- The `connector_idempotency_key` database column rename is deferred. The column stores `sha256:*` hash values only; API/frontend expose `connectorIdempotencyKeyHash`.
- Secret-scan noise reviewed: examples/dev placeholders only; no ignored local secrets staged.
- Local untracked `sample.csv` was removed locally and is not part of the PR.
- Production connector activation remains future work only and requires separate security/runbook acceptance.

## Explicit non-goals

- No real ERP/1C writes.
- No external network connector execution.
- No raw secret handling.
- No inventory mutation.
- No bot-triggered connector commands.
- No autonomous production connector activation.

## Reviewer checklist

- Review Stage 9B safety invariants: demo-only execution, idempotency hash, replay audit, policy block, retry/cancel behavior, and tenant isolation.
- Review `docs/security`.
- Review `docs/runbooks`.
- Review frontend contract naming, especially `connectorIdempotencyKeyHash`.
- Review backend targeted tests for Stage 9B integration safety.
- Review full-suite verification notes and sandbox Maven blocker.

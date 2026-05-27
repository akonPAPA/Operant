# Codex Re-onboarding Runbook

Use this runbook when Codex state/cache was cleared and the repository must be re-read from disk.

## Rules

- Do not reset git.
- Do not delete files.
- Do not overwrite unrelated files.
- Do not normalize the dirty worktree.
- Do not change ports.
- Do not change migrations unless a test proves a minimal fix is required.
- Do not add OCR, LLM providers, quote/order automation, ERP writes, or production outbound chat behavior during re-onboarding.

## Survival Check

From `C:\OrderPilot\OrderPilot-Core`, verify:

```powershell
Get-ChildItem -Force
Test-Path apps\core-api
Test-Path apps\web-dashboard
Test-Path apps\ai-worker
Test-Path infra\docker\docker-compose.yml
Test-Path scripts\seed-demo-data\seed-core-v1.ps1
Test-Path packages\test-fixtures
Test-Path docs
Test-Path .github
```

Current checkpoint result: all required paths exist.

## Git Checkpoint

Run:

```powershell
git status --short
git branch --show-current
git log -1 --oneline
```

Current checkpoint:

- Branch: `docs/stage-12-company-readiness`
- Latest commit: `72fecad feat: complete data foundation runtime baseline`
- Dirty state before these handoff docs were added: 56 modified tracked paths and 29 untracked paths.
- Dirty state after these handoff docs were added: 56 modified tracked paths and 32 untracked paths.
- Assessment: worktree is dirty but consistent; do not revert or clean it during re-onboarding.

## Verification Commands

Backend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn test
```

Frontend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm.cmd run typecheck
npm.cmd run lint
npm.cmd run build
```

AI worker:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\ai-worker
.\.venv\Scripts\python.exe -m pytest
```

Docker:

```powershell
cd C:\OrderPilot\OrderPilot-Core
docker compose -f infra\docker\docker-compose.yml config
```

Seed:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File .\scripts\seed-demo-data\seed-core-v1.ps1
```

The seed script requires core-api to be running at `http://localhost:8080`.

## Current Checkpoint Results

- Backend Maven tests: PASS.
- Frontend typecheck: PASS.
- Frontend lint: PASS.
- Frontend build: PASS.
- AI worker pytest: PASS, 11 tests passed.
- Docker Compose config: PASS.
- Demo seed script: FAIL only because core-api was not running.

## Stage Audit Summary

Stage 1 is implemented: Spring Boot core-api, health endpoint, PostgreSQL/Flyway, tenant/user/role/permission/audit/idempotency foundation, structured errors, Next.js shell, Python worker placeholder, Docker Compose, and CI/docs.

Stage 2 is implemented: tenant-owned products/customers/inventory/pricing/aliases/OEM/substitutes/compatibility, import jobs/staging/validation/activation, demo CSV fixtures, seed script, tenant-scoped reads, and audit for import mutations.

Stage 3 is implemented: file upload, API upload, email webhook stub, Telegram webhook intake, `InboundDocument`, `ChannelMessage`, `InboundEventLedger`, `WebhookEvent`, `ProcessingJob`, object storage abstraction, frontend intake visibility, docs, and fixtures.

Stage 4 is not the approved active task for this checkpoint. The repository contains pre-existing Stage 4+ files and docs, including extraction and quote/order-looking surfaces. Treat them as experimental/pre-existing until a dedicated reconciliation task is requested.

## Next Prompt Recommendation

Ask Codex to start Stage 4 only after acknowledging the current dirty worktree and later-stage experimental files. The Stage 4 prompt should limit work to an AI-assisted understanding pipeline skeleton: text extraction interface, LLM provider abstraction, extraction schema, confidence fields, evidence references, and advisory-only persisted results. It must explicitly forbid quote/order writes and ERP writes.

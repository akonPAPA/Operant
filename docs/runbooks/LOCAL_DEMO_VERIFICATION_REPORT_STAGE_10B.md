# Local Demo Verification Report - Stage 10B

Stage: Stage 10B - Mock-only Shadow Mode and Pilot Metrics Skeleton

Date: 2026-05-19

## Scope

Stage 10B adds backend and ai-worker contracts for mock-only pilot/shadow-mode evidence:

- record shadow-mode advisory predictions
- record human corrections, acceptances, and rejections
- aggregate pilot-readiness metrics
- document the correction tracking contract and remaining blockers

Stage 10A documentation/readiness artifacts were preserved and not rewritten.

## Safety Confirmation

- Stage 10B is mock-only.
- AI remains advisory only.
- No real provider integration exists.
- No OpenAI, Anthropic, Azure, or production provider key requirement was added.
- No ERP, 1C, accounting, warehouse, email, bot reply, quote submission, or order submission writes were added.
- No external connector write mode was added.
- No frontend redesign was added.
- Existing quote/order approval flows were not changed.

## Verification Commands

Run from `C:\OrderPilot\OrderPilot-Core`.

```powershell
.\scripts\start-local-demo.ps1
.\scripts\check-local-demo.ps1 -AllowFixtureMode
.\scripts\check-no-secrets.ps1
cd apps\core-api
mvn test -Dtest=PilotShadowModeServiceTest,Stage10BMigrationFileTest
cd ..\ai-worker
python -m pytest tests\test_stage10b_shadow_mode.py
```

## Expected Results

- Local demo startup completes or reports only known fixture-mode limitations.
- Local demo check passes with `-AllowFixtureMode`.
- No-secrets check passes.
- Backend pilot/shadow/correction metric tests pass.
- Ai-worker mock shadow-mode fixture tests pass.
- No test requires real AI provider keys.
- No test performs ERP/1C/accounting/warehouse writes.

## Observed Results

- `.\scripts\start-local-demo.ps1`: passed. Backend and frontend were already listening and responding.
- `.\scripts\check-local-demo.ps1 -AllowFixtureMode`: passed. Health, demo API probes, and dashboard routes returned HTTP 200.
- `.\scripts\check-no-secrets.ps1`: passed. No obvious hardcoded secrets found.
- `mvn test "-Dtest=PilotShadowModeServiceTest,Stage10BMigrationFileTest"`: passed. 6 tests run, 0 failures, 0 errors.
- `python -m pytest tests\test_stage10b_shadow_mode.py`: blocked because neither `python`/`py` nor bundled `pytest` was available on PATH/runtime.
- Bundled Python direct execution of the Stage 10B ai-worker mock shadow-mode test functions: passed.

## Pilot Metric Interpretation

Metrics are pilot-readiness indicators only:

- total shadow runs
- reviewed runs
- accepted/corrected/rejected counts
- human correction rate
- average confidence
- prediction and correction breakdowns

They are not production ROI proof and should not be presented as customer-facing automation claims until production controls and a real pilot dataset exist.

## Remaining Blockers

- ChangeRequest model
- transactional outbox
- connector idempotency
- external-write rollback contracts
- production auth/RBAC proof if still missing
- real provider safety/secret-management harness
- production pilot dashboards/UI

## Stage 10C Recommendation

Implement a ChangeRequest and transactional outbox design for controlled write intent capture, still without enabling external connector execution by default.

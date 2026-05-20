# Stage 9J Demo Evidence

## Scope

This folder documents the Stage 9J investor demo evidence pass. It intentionally does not add product features, redesign the investor demo UI, change frontend routes, change backend behavior, change AI logic, change dependencies, add secrets, or delete Docker volumes.

## Verification Summary

- Date/time: 2026-05-19T15:33:34.4267114+05:00
- Docker: PASS
- Docker Compose: PASS
- Postgres: PASS, healthy
- Redis: PASS, healthy
- Backend: PASS, `http://localhost:8080/api/v1/health`
- Frontend: PASS, `http://localhost:3000/demo`
- `check-local-demo.ps1 -AllowFixtureMode`: PASS
- `check-no-secrets.ps1`: PASS
- Final Stage 9J status: `PASS_WITH_LIMITATIONS`

## Checked Routes

| Route | Status | Evidence |
| --- | --- | --- |
| `http://localhost:3000/` | PASS | HTTP 200; Command Center shell rendered. |
| `http://localhost:3000/demo` | PASS | HTTP 200; required investor demo panels and trust language rendered. |
| `http://localhost:3000/command-center` | PASS | HTTP 200; Command Center shell rendered. |
| `http://localhost:3000/reconciliation` | PASS | HTTP 200; canonical mismatch values rendered. |
| `http://localhost:3000/audit-log` | PASS | HTTP 200; Audit / Security content rendered. |

## Required Demo Content Found

On `/demo`:

- `OrderPilot Demo Parts Distributor`
- `Need brake pads for Toyota Camry 2018, 20 pcs, wholesale, Almaty.`
- Opening stock `150`
- Sold `34`
- Expected stock `116`
- Actual stock `100`
- Mismatch `-16`
- `Total sales amount`
- `Channel breakdown`
- `AI/bot cannot approve a quote`
- `Bot cannot create a final order`
- `No ERP write exists`
- `Tenant isolation`

On `/reconciliation`:

- `Canonical demo mismatch`
- `150`
- `116`
- `100`
- `-16`

## Screenshot Status

No screenshot binaries are stored here yet.

Reason: the repository has no screenshot automation dependency, adding one is forbidden for this pass, and the in-app browser screenshot call timed out during capture attempts. This matches the fallback already documented in `docs\investor\DEMO_SCREENSHOT_CHECKLIST.md` and `docs\runbooks\LOCAL_DEMO_RUNBOOK.md`.

## Manual Screenshot Capture Instructions

Use a normal desktop browser if screenshot PNG files are needed for the investor packet:

1. Run:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File .\scripts\check-local-demo.ps1 -AllowFixtureMode
```

2. Open `http://localhost:3000/demo`.
3. Capture:
   - hero and timeline;
   - Telegram RFQ panel;
   - reconciliation panel;
   - analytics summary panel;
   - security/trust panel.
4. Open and capture:
   - `http://localhost:3000/command-center`;
   - `http://localhost:3000/reconciliation`;
   - `http://localhost:3000/audit-log`.
5. Store screenshot files in `docs\investor\screenshots\` only if binary screenshots are intentionally included in the documentation package.

## Related Files

- `docs\investor\DEMO_SCREENSHOT_CHECKLIST.md`
- `docs\investor\DEMO_SCREENSHOT_CHECKLIST_STAGE_9J_COMPLETED.md`
- `docs\runbooks\LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9J.md`

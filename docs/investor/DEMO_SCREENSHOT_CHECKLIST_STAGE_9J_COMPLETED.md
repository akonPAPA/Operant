# Demo Screenshot Checklist - Stage 9J Completed

## Metadata

- Checked date/time: 2026-05-19T15:33:34.4267114+05:00
- Verifier: Codex
- Active repository root: `C:\OrderPilot\OrderPilot-Core`
- Source checklist: `docs\investor\DEMO_SCREENSHOT_CHECKLIST.md`
- Stage: 9J investor demo evidence capture

## Baseline Verification

| Check | Status | Notes |
| --- | --- | --- |
| Docker version | PASS | Docker version 29.4.3, build 055a478. |
| Docker Compose version | PASS | Docker Compose version v5.1.3. |
| Docker Compose services | PASS | Postgres and Redis healthy after starting repo-defined services. |
| `start-local-demo.ps1` | PASS | Reused existing backend PID 9764 and frontend PID 8064. |
| `check-local-demo.ps1 -AllowFixtureMode` | PASS | Backend, frontend, API probes, and key dashboard routes passed. |
| `check-no-secrets.ps1` | PASS | No obvious hardcoded secrets found. |

## Required Screenshots and Visual Checks

| Item | Status | Route URL | What should be visible | Demo data realistic enough | Visual issue or missing data |
| --- | --- | --- | --- | --- | --- |
| `/demo` hero and timeline | PASS | `http://localhost:3000/demo` | `OrderPilot Demo Parts Distributor`, B2B distributor context, and timeline from Telegram RFQ through audit/security. | Yes | None found in route/content evidence. |
| `/demo` Telegram RFQ panel | PASS | `http://localhost:3000/demo` | `Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.` | Yes | None found. |
| `/demo` reconciliation panel | PASS | `http://localhost:3000/demo` | Opening `150`, sold `34`, expected `116`, actual `100`, mismatch `-16`, severity `HIGH`. | Yes | None found. |
| `/demo` analytics summary panel | PASS | `http://localhost:3000/demo` | Total sales amount note and channel breakdown. | Yes | None found; backend analytics probe passed. |
| `/demo` security/trust panel | PASS | `http://localhost:3000/demo` | No quote approval, no final order, no ERP write, audit, and tenant isolation language. | Yes | None found. |
| `/command-center` shell | PASS | `http://localhost:3000/command-center` | Command Center dashboard shell and investor demo path. | Yes | None found. |
| `/reconciliation` page | PASS | `http://localhost:3000/reconciliation` | Canonical demo mismatch copy. | Yes | None found. |
| `/audit-log` Audit / Security page | PASS | `http://localhost:3000/audit-log` | Audit / Security page and trust boundary copy. | Yes | None found. |

## Route Evidence

| Route | HTTP status | Key content found |
| --- | --- | --- |
| `/` | 200 | Command Center shell; Audit / Security navigation. |
| `/demo` | 200 | Tenant, RFQ text, stock values, analytics labels, and trust language. |
| `/command-center` | 200 | Command Center shell. |
| `/reconciliation` | 200 | Canonical demo mismatch; `150`, `116`, `100`, `-16`. |
| `/audit-log` | 200 | Audit / Security route and trust boundary content. |

## Screenshot Capture Status

Status: `PASS_WITH_LIMITATIONS`

No screenshot binaries were added in Stage 9J. The repository does not include a screenshot automation dependency, and adding Playwright/Puppeteer or other screenshot dependencies is forbidden for this pass. The in-app browser screenshot call timed out during evidence capture attempts, so the supported fallback is manual capture.

Manual screenshot instructions:

1. Confirm the baseline:

```powershell
cd C:\OrderPilot\OrderPilot-Core
powershell -ExecutionPolicy Bypass -File .\scripts\check-local-demo.ps1 -AllowFixtureMode
```

2. Open these URLs in a normal desktop browser:

```text
http://localhost:3000/demo
http://localhost:3000/command-center
http://localhost:3000/reconciliation
http://localhost:3000/audit-log
```

3. Capture the eight checklist views above.
4. Save screenshots under `docs\investor\screenshots\` only if binary screenshot files are intentionally part of the investor documentation package.
5. Do not change UI, seed production data, add dependencies, or add real secrets for screenshot capture.

## Evidence References

- `docs\runbooks\LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9J.md`
- `docs\investor\demo-evidence\README_STAGE_9J.md`

## Final Status

`PASS_WITH_LIMITATIONS`

All required routes and visible content checks passed. The only limitation is that screenshot PNG files remain a manual capture step because no repo-approved screenshot automation exists.

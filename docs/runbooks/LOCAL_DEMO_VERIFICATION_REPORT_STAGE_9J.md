# Local Demo Verification Report - Stage 9J

## Metadata

- Date/time: 2026-05-19T15:33:34.4267114+05:00
- Verifier: Codex
- Active repository root: `C:\OrderPilot\OrderPilot-Core`
- Purpose: investor demo evidence capture after Stage 9I PASS.

## Purpose

Stage 9J captures the investor demo checklist/evidence package using the existing Stage 9I local demo baseline.

This was a documentation and evidence pass only. No product features, UI redesigns, frontend routes, backend domain behavior, AI logic, dependencies, real secrets, Docker volumes, or Stage 10 implementation were changed.

## Environment Assumptions

- Windows host using PowerShell.
- Docker Desktop is available and may need to be launched if the daemon pipe is asleep.
- Local infrastructure uses `infra\docker\docker-compose.yml`.
- Docker volumes were preserved. No `docker compose down -v` was run.
- Backend and frontend may already be running; `start-local-demo.ps1` safely verifies and reuses expected processes.
- Frontend screenshot automation is not a repo dependency. Manual screenshot capture remains the supported fallback when automated browser screenshot capture is unavailable.

## Commands Run

```powershell
docker --version
docker compose version
docker compose ps
docker compose up -d postgres redis
docker compose ps
powershell -ExecutionPolicy Bypass -File .\scripts\start-local-demo.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\check-local-demo.ps1 -AllowFixtureMode
powershell -ExecutionPolicy Bypass -File .\scripts\check-no-secrets.ps1
```

Route/content probes:

```powershell
Invoke-WebRequest http://localhost:3000/
Invoke-WebRequest http://localhost:3000/demo
Invoke-WebRequest http://localhost:3000/command-center
Invoke-WebRequest http://localhost:3000/reconciliation
Invoke-WebRequest http://localhost:3000/audit-log
```

## Docker and Compose Status

Docker:

```text
Docker version 29.4.3, build 055a478
```

Docker Compose:

```text
Docker Compose version v5.1.3
```

Initial `docker compose ps` found Docker Desktop daemon asleep. Docker Desktop was launched, then only the repo-defined local services were started:

```powershell
docker compose up -d postgres redis
```

Final status:

```text
NAME                IMAGE                SERVICE    STATUS
docker-postgres-1   postgres:16-alpine   postgres   Up 6 minutes (healthy)
docker-redis-1      redis:7-alpine       redis      Up 6 minutes (healthy)
```

## Backend Status

`start-local-demo.ps1` reused the existing backend process:

```text
WARN: Backend port 8080 is already listening (PID 9764). The script will verify the expected endpoint before starting a new process.
Core API already responds at http://localhost:8080/api/v1/health
```

Backend result: PASS.

## Frontend Status

`start-local-demo.ps1` reused the existing frontend process:

```text
WARN: Frontend port 3000 is already listening (PID 8064). The script will verify the expected endpoint before starting a new process.
Web dashboard already responds at http://localhost:3000/demo
```

Frontend result: PASS.

## Local Demo Check

Command:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-local-demo.ps1 -AllowFixtureMode
```

Result: PASS.

Passing evidence included:

- Java, Maven, Node, and `npm.cmd` found.
- Frontend `node_modules` and `.env.local` found.
- `NEXT_PUBLIC_CORE_API_URL` points to `http://localhost:8080`.
- Demo tenant/product/location IDs are configured.
- Postgres reachable at `localhost:5432`.
- Next.js build output found.
- Backend port `8080` and frontend port `3000` listening.
- Core API health returned HTTP 200.
- Demo Telegram RFQ webhook returned HTTP 200.
- Demo inventory reconciliation run returned HTTP 200.
- Demo reconciliation cases returned HTTP 200.
- Demo commerce analytics summary returned HTTP 200.
- Key dashboard routes returned HTTP 200.

## Secret Scan

Command:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check-no-secrets.ps1
```

Result: PASS.

Evidence:

```text
No obvious hardcoded secrets found.
```

## Demo Routes Checked

| Route | Result | Evidence |
| --- | --- | --- |
| `http://localhost:3000/` | PASS | HTTP 200; redirects/renders the Command Center shell. |
| `http://localhost:3000/demo` | PASS | HTTP 200; includes investor demo tenant, RFQ, reconciliation, analytics, and trust panels. |
| `http://localhost:3000/command-center` | PASS | HTTP 200; command center shell visible in rendered content. |
| `http://localhost:3000/reconciliation` | PASS | HTTP 200; canonical mismatch content present. |
| `http://localhost:3000/audit-log` | PASS | HTTP 200; Audit / Security content present. |

## Screenshot Checklist Status

| Checklist item | Status | Route | Expected visible content | Demo data realistic enough | Notes |
| --- | --- | --- | --- | --- | --- |
| `/demo` hero and timeline | PASS | `http://localhost:3000/demo` | OrderPilot Demo Parts Distributor, B2B distributor context, timeline from Telegram RFQ through audit/security. | Yes | Rendered content found. |
| `/demo` Telegram RFQ panel | PASS | `http://localhost:3000/demo` | `Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.` | Yes | Canonical RFQ text found. |
| `/demo` reconciliation panel | PASS | `http://localhost:3000/demo` | Opening `150`, sold `34`, expected `116`, actual `100`, mismatch `-16`. | Yes | Canonical mismatch values found. |
| `/demo` analytics summary panel | PASS | `http://localhost:3000/demo` | Sales amount note and channel breakdown. | Yes | Analytics labels found; backend analytics probe passed. |
| `/demo` security/trust panel | PASS | `http://localhost:3000/demo` | No quote approval, no final order, no ERP write, audit, tenant isolation. | Yes | Required trust language found. |
| `/command-center` shell | PASS | `http://localhost:3000/command-center` | Command Center shell and investor demo path. | Yes | Route returned HTTP 200. |
| `/reconciliation` page | PASS | `http://localhost:3000/reconciliation` | Canonical demo mismatch. | Yes | Route returned HTTP 200 and mismatch values were present. |
| `/audit-log` Audit / Security page | PASS | `http://localhost:3000/audit-log` | Audit / Security route and trust boundary copy. | Yes | Route returned HTTP 200. |

## Visual and Demo Limitations

- Automated screenshot binaries were not committed in this pass.
- The repo has no Playwright/Puppeteer screenshot dependency, and adding one is forbidden for this evidence pass.
- The in-app browser screenshot call timed out during capture attempts, so Stage 9J follows the existing checklist/runbook fallback: use the local route checks plus manual screenshot instructions.
- No visual issue or missing demo data was found in the route/content evidence.
- If investor-facing image files are required for a packet, capture them manually from the listed local URLs and place them under `docs\investor\screenshots\` only if the team intentionally wants binary screenshot files in the documentation package.

## Evidence Files Created or Updated

- `docs\runbooks\LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9J.md`
- `docs\investor\DEMO_SCREENSHOT_CHECKLIST_STAGE_9J_COMPLETED.md`
- `docs\investor\demo-evidence\README_STAGE_9J.md`

## Final Status

`PASS_WITH_LIMITATIONS`

The Stage 9I local demo baseline is still healthy, all required checks passed, required investor demo routes responded, and required checklist content is present. The only limitation is screenshot binary capture tooling: no repo-approved screenshot dependency exists, and the in-app screenshot call timed out, so manual screenshot capture remains the supported evidence step.

## Recommendation

Start Stage 10 pilot-readiness.

Stage 10 is safe to begin from a product/readiness perspective. If a polished investor packet needs actual PNG files, capture the manual screenshots first without changing product behavior.

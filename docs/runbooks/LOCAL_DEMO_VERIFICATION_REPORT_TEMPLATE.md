# Local Demo Verification Report Template

## Metadata

- Date/time:
- Verifier:
- Commit hash:
- Branch:

## Backend Verification

- Command: `cd C:\OrderPilot\OrderPilot-Core\apps\core-api; mvn clean test`
- Result:
- Tests run:
- Failures:
- Errors:
- Skipped:
- Notes:

## Frontend Verification

- Command: `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm run lint`
- Result:

- Command: `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm run typecheck`
- Result:

- Command: `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm run build`
- Result:

- Command: `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm test`
- Result:

## Security Verification

- Command: `powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\check-no-secrets.ps1`
- Result:
- Notes:

## Runtime Verification

- Backend runtime on `http://localhost:8080`:
- Frontend runtime on `http://localhost:3000`:
- Local Postgres reachable:
- `.env.local` populated with seeded demo IDs:
- Command: `powershell -ExecutionPolicy Bypass -File C:\OrderPilot\OrderPilot-Core\scripts\check-local-demo.ps1`
- Result:

## Route Probe Table

| Route | Expected | Result | Notes |
| --- | --- | --- | --- |
| `/demo` | HTTP 200 |  |  |
| `/command-center` | HTTP 200 |  |  |
| `/inbox` | HTTP 200 |  |  |
| `/bot-conversations` | HTTP 200 |  |  |
| `/bot/conversations` | HTTP 200 or redirect to `/bot-conversations` |  |  |
| `/reconciliation` | HTTP 200 |  |  |
| `/analytics` | HTTP 200 |  |  |
| `/audit-log` | HTTP 200 |  |  |
| `/integrations` | HTTP 200 |  |  |

## Manual Screenshot Checklist

Source checklist: `docs/investor/DEMO_SCREENSHOT_CHECKLIST.md`

| Screenshot | Status | Notes |
| --- | --- | --- |
| `/demo` hero and timeline |  |  |
| Telegram RFQ panel |  |  |
| Reconciliation panel with `-16` mismatch |  |  |
| Analytics summary |  |  |
| Security/trust panel |  |  |
| Command Center shell |  |  |
| Reconciliation page |  |  |
| Audit / Security page |  |  |

## Known Issues

- 

## Final Verdict

Choose one:

- `PASS`
- `PASS_WITH_LIMITATIONS`
- `FAIL`

Verdict:

Rationale:

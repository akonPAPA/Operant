# Demo Readiness Checklist

Date: 2026-05-23

Use this checklist before investor demos, design-partner calls, and developer handoffs.

## Local Verification

- Backend: `cd C:\OrderPilot\OrderPilot-Core\apps\core-api; mvn test`
- Frontend typecheck: `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm.cmd run typecheck`
- Frontend lint: `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm.cmd run lint`
- Frontend build: `cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard; npm.cmd run build`
- AI worker, only when changed: `cd C:\OrderPilot\OrderPilot-Core\apps\ai-worker; .\.venv\Scripts\python.exe -m pytest`
- Docker, only when infrastructure changes: `cd C:\OrderPilot\OrderPilot-Core; docker compose -f infra\docker\docker-compose.yml config`

## Demo Seed

- Seed script: `scripts/seed-demo-data/seed-core-v1.ps1`
- Fixture roots: `packages/test-fixtures/stage2-demo` and `packages/test-fixtures/stage3-intake`
- Expected behavior: idempotent local demo setup through core-api endpoints.
- If core-api is not running at `http://localhost:8080`, seed verification is incomplete, not a product failure.

Demo data should include safe fake tenant data only:

- tenant and demo user/role assumptions;
- customers, products, aliases, OEM references, compatibility, substitutes;
- inventory snapshots;
- price, discount, and margin rules;
- inbound Telegram/API/email/file intake examples;
- advisory extraction and validation examples;
- review cases and bot handoff examples;
- enough workflow records to populate analytics.

## Demo Flow

- Scenario 1: inbound Telegram or bot RFQ/message.
- Scenario 2: uploaded PDF/Excel-style intake or API document upload.
- Scenario 3: advisory extraction with confidence and evidence.
- Scenario 4: deterministic validation issues and approval requirements.
- Scenario 5: operator review cockpit and grouped issues.
- Scenario 6: bot handoff or needs-review flow.
- Scenario 7: read-only analytics command center.
- Scenario 8: security and trust explanation.

## Security Readiness

- Confirm tenant header is set for tenant-scoped API calls.
- Confirm explicit `X-OrderPilot-Permissions` checks deny missing permissions when provided.
- Confirm analytics is read-only.
- Confirm bot runtime does not approve orders, create final quotes/orders, or send production customer replies.
- Confirm review approval means approved for next stage only.
- Confirm AI output stays advisory and does not write customer, product, inventory, price, quote, order, ERP, or accounting records.
- Confirm no real secrets are present in docs, fixtures, or request examples.

## Observability Readiness

Monitor or inspect:

- API structured errors.
- Processing jobs by status and stale/failed jobs.
- Extraction failures and low-confidence counts.
- Validation status and top issue codes.
- Review backlog and escalations.
- Bot handoff volume and unknown intents.
- Webhook failures/replays.
- Analytics latency and read-only behavior.

## Backup And Restore

For local/demo environments:

- Export PostgreSQL with `pg_dump` before important demos.
- Record database name, migration level, seed script version, and fixture paths.
- Restore into a clean database and rerun backend smoke tests before claiming restore confidence.
- Keep object-storage demo payloads alongside database backups when raw payload references are needed.

Production backup/restore, WORM audit storage, and disaster recovery drills are future hardening tasks.

## Non-Scope Reminder

Stage 9 does not implement production ERP writes, 1C/SAP/Dynamics/Oracle writes, connector execution, Local Windows Connector, desktop agent, final quote/order automation, paid OCR/LLM calls, production outbound messaging, or production WhatsApp/WeChat runtime.

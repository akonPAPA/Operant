# Stage 13B Demo Runbook

## Purpose

Run the investor-ready local walkthrough for Stage 13B. This runbook keeps the demo honest: the bot routes and suggests, operators review, quote approval is controlled, and external execution remains disabled.

## Prerequisites

- Java 21 and Maven are available.
- Node.js/npm dependencies are installed in `apps/web-dashboard`.
- Python worker virtualenv exists at `apps/ai-worker/.venv`.
- PostgreSQL and Redis are running if using the full local stack.
- PowerShell runs from `C:\OrderPilot\OrderPilot-Core`.

## Optional Seed Data

Use existing fixture data; do not create ad hoc demo records in production paths.

```powershell
cd C:\OrderPilot\OrderPilot-Core
.\scripts\seed-demo-data\seed-core-v1.ps1
```

Expected fixture highlights:

- Customer: `CUST-001` / Steppe Logistics.
- Out-of-stock product: `PAD-OE-04465`.
- In-stock substitute: `PAD-SUB-ADV`.
- Approval-required substitute: `PAD-SUB-ECON`.
- Warehouse/location: `WH-ALM`.
- Price and margin rules from `stage2-demo`.

## Start Local Services

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn spring-boot:run
```

In a second terminal:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm.cmd run dev
```

Set `NEXT_PUBLIC_DEMO_TENANT_ID` to the seeded tenant before starting the dashboard when using backend-backed demo screens.

## Demo Path

1. Open `http://localhost:3000/demo`.
2. Click `Send demo Telegram RFQ`.
3. Confirm the RFQ text is `Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.`
4. Confirm the panel shows operator review and `External execution: DISABLED`.
5. Open `http://localhost:3000/bot-conversations`.
6. Confirm the captured conversation shows intent, policy decision, handoff reason, and no unsafe action controls.
7. Open `http://localhost:3000/quote-review`.
8. Open a quote review when seeded/backend data exists and show validation, substitutes, approval needs, and audit timeline.
9. Open `http://localhost:3000/quotes`.
10. Use the defaults `CUST-001`, `PAD-OE-04465`, `WH-ALM`, `2 EA`.
11. Create the draft quote and show approval controls, validation/substitute context, audit correlation, and disabled external execution.

## Expected Backend Behavior

- Bot webhook/simulation creates tenant-scoped channel/bot records.
- RFQ and sensitive intents require operator review by default.
- Bot handoff creation is idempotent per message.
- Quote creation uses backend validation and policy services.
- Approval, reject, request-changes, and internal conversion use the quote approval state machine.
- Conversion remains internal-only; connector/ERP writes are not executed.
- Audit events are append-only records with tenant context and explicit disabled external execution on relevant paths.

## Verification

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn test

cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm.cmd run lint
npm.cmd exec tsc -- --noEmit --incremental false
npm.cmd test
npm.cmd run build

cd C:\OrderPilot\OrderPilot-Core\apps\ai-worker
.\.venv\Scripts\python.exe -m pytest
```

## What Not To Claim

- Do not claim live ERP/1C execution.
- Do not claim autonomous quote approval.
- Do not claim production Telegram outbound messaging.
- Do not claim the bot can safely disclose customer-specific prices without operator-controlled identity and policy checks.
- Do not claim every audit event in the historical system has normalized metadata.

## Known Limitations

- Seeded IDs must be copied into environment variables for some demo controls.
- Some backend-backed pages show empty states when seed data has not been loaded.
- Some audit entries still display technical metadata because the full audit read model is not a Stage 13B scope item.

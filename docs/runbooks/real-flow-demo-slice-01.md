# Real Flow Demo Slice 01

## What this flow proves

This slice connects the existing deterministic Telegram demo RFQ to a visible operator workflow:

```text
/demo Telegram RFQ
-> tenant-scoped RFQ handoff
-> advisory AI Work suggestion
-> operator starts review
-> review-required draft quote
-> audited CONVERTED handoff
-> visible audit/outbox/external-execution status
```

The dashboard shows the source request, typed AI summary/display fields/next-action candidates,
risk and confidence, the resulting draft quote status, and the execution safety state.

## What this flow does not prove

- production SSO, identity provisioning, or BFF behavior;
- real ERP, 1C, accounting, inventory, pricing, or customer-master writes;
- autonomous AI approval or mutation;
- production connector delivery, outbox execution, or ChangeRequest execution;
- production-scale performance or full CI.

## Local startup

From `C:\OrderPilot\OrderPilot-Core`:

```powershell
docker compose -f infra\docker\docker-compose.yml up -d postgres redis
```

Start the backend:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\core-api
mvn spring-boot:run
```

After the backend migrations are ready, seed the deterministic local catalog and update the
frontend environment:

```powershell
cd C:\OrderPilot\OrderPilot-Core
.\scripts\seed-local-demo.ps1 -UpdateFrontendEnv
```

Expected local demo seed facts:

- product `PAD-OE-04465` and normalized/alias code `PADOE04465` exist;
- an inventory snapshot and price rules exist;
- substitute candidates exist;
- no Telegram, LLM, ERP/1C, external connector network, or production seeder is required.

Configure `apps\web-dashboard\.env.local` from `.env.local.example`:

```dotenv
NEXT_PUBLIC_CORE_API_URL=http://localhost:8080
NEXT_PUBLIC_ORDERPILOT_DEMO_MODE=true
NEXT_PUBLIC_DEMO_TENANT_ID=11111111-1111-4111-8111-111111111111
```

Start the dashboard:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm run dev
```

## Expected UI path

1. Open `http://localhost:3000/demo`.
2. Click **Send demo Telegram RFQ**. The deterministic request is:
   `Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.`
3. Open `http://localhost:3000/channels/rfq-handoffs`.
4. Open the pending Telegram handoff.
5. Click **Generate suggestion**. Confirm the advisory summary, risk, confidence, and any
   next-action candidates are visible.
6. Click **Start review**.
7. Click **Create draft quote**.
8. Confirm the UI shows exactly one line with `PAD-OE-04465`, normalized SKU `PADOE04465`,
   quantity `2`, UOM `EA`, the resolved product name, validation status, and any issue codes.
9. Confirm the quote header shows the quote number, review/validation status, `Audit: RECORDED`,
   `Outbox: NOT_REQUESTED`, and `External write safety: NO_EXTERNAL_WRITE`.

## Expected backend state

- the handoff transitions `PENDING_REVIEW -> IN_REVIEW -> CONVERTED`;
- one tenant-scoped draft quote is created with source type `RFQ_HANDOFF`;
- the draft contains one parsed line resolved to the seeded `PAD-OE-04465` product;
- the quote remains review-required and is not approved;
- retries return the same quote through a backend-derived idempotency key;
- AI Work remains a separate advisory record;
- audit records include AI suggestion creation, draft creation/validation, and handoff conversion;
- no connector command, sandbox execution, compensation plan, ChangeRequest, or outbox event is
  created by this flow.

## Targeted tests

```powershell
cd C:\OrderPilot\OrderPilot-Core
mvn -f apps/core-api/pom.xml "-Dtest=RfqHandoffDraftQuoteServiceTest,RfqHandoffDraftQuoteControllerTest,AiWorkControllerAuthorityBoundaryTest" test

cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm test
```

Broader stable-scope checks:

```powershell
cd C:\OrderPilot\OrderPilot-Core
mvn -f apps/core-api/pom.xml -DskipITs test
git diff --check

cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard
npm run lint
npm run typecheck
npm test
npm run build
```

## Safety invariants preserved

- The frontend submits only the safe handoff route handle and an empty intent body.
- Tenant, actor, role, source text, status, and idempotency are backend-owned.
- The handoff is tenant-locked and must already be `IN_REVIEW`.
- Cross-tenant lookup fails as not found.
- AI public responses use `summary`, `displayFields`, `evidence`, `nextActionCandidates`, and
  `riskFlags`; raw AI payload fields are not used.
- Draft creation never treats AI output as trusted quote input.
- The response omits tenant/actor/source-storage/audit-event/idempotency internals.
- No external write, connector execution, ChangeRequest, or outbox command is triggered.

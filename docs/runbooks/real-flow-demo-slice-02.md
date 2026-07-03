# Real Flow Demo Slice 02

## Outcome

This slice completes the visible local flow:

```text
Telegram demo RFQ
-> advisory AI suggestion
-> operator review
-> review-required draft quote
-> operator COMPLETE_DEMO or DECLINE_DEMO decision
-> SAFE_DEMO_TERMINAL
-> audit RECORDED
-> externalExecution DISABLED
-> connector NOT_INVOKED
```

The terminal decision is a demo workflow result. It is not quote approval and cannot trigger ERP,
1C, accounting, connector, inventory, pricing, or customer-master writes.

## Start locally

From `C:\OrderPilot\OrderPilot-Core`:

```powershell
docker compose -f infra\docker\docker-compose.yml up -d postgres redis
.\scripts\seed-local-demo.ps1 -UpdateFrontendEnv
```

In one terminal:

```powershell
mvn -f apps\core-api\pom.xml spring-boot:run
```

In another terminal:

```powershell
npm --prefix apps\web-dashboard run dev
```

Use the Slice 01 demo environment values in `apps\web-dashboard\.env.local`. No Telegram, LLM,
ERP/1C, accounting, or connector service is required.

## Complete the visible flow in 3–5 minutes

1. Open `http://localhost:3000/demo` and click **Send demo Telegram RFQ**.
2. Open `http://localhost:3000/channels/rfq-handoffs` and open the new pending handoff.
3. Click **Generate suggestion**. Confirm **Advisory only** is visible.
4. Click **Start review**, then **Create draft quote**.
5. Review the quote status, validation state, line, issues, and the existing audit/outbox/external
   write safety summary.
6. Enter a decision note such as `Reviewed for local demo; no external action requested`.
7. Click **Complete safe demo**. Use **Decline demo draft** only when demonstrating the alternate
   terminal decision.
8. Confirm the workspace shows:
   - decision `COMPLETE_DEMO` or `DECLINE_DEMO`;
   - quote state `DEMO_COMPLETED` or `DEMO_DECLINED`;
   - safe terminal state `SAFE_DEMO_TERMINAL`;
   - audit `RECORDED`;
   - external execution `DISABLED`;
   - connector call `NOT_INVOKED`;
   - outbox `NOT_REQUESTED`.

## Decision API contract

```http
POST /api/v1/quotes/drafts/from-rfq-handoff/{handoffId}/decision
Idempotency-Key: rfq-handoff-decision-{handoffId}-{decision}
X-OrderPilot-Permissions: QUOTE_ACTION
Content-Type: application/json

{
  "decision": "COMPLETE_DEMO",
  "note": "Reviewed for local demo; no external action requested"
}
```

The client sends only `decision` and `note`. The backend resolves tenant, actor, quote role, RFQ
handoff, associated draft quote, current state, valid transition, audit, and terminal execution
safety. A retry with the same idempotency key and business intent returns the stored safe response.

The response exposes only public workflow handles, quote number, decision/terminal display states,
and fixed safety summaries. It omits tenant/actor IDs, raw AI payloads, idempotency values, audit
internals, connector internals, credentials, and raw errors.

## Expected backend state

- the handoff remains `CONVERTED`;
- its backend-associated `RFQ_HANDOFF` draft quote moves only from `NEEDS_REVIEW`,
  `SUBSTITUTION_REVIEW`, or `READY_FOR_APPROVAL` to `DEMO_COMPLETED` or `DEMO_DECLINED`;
- validation status and `requiresHumanReview` are preserved so the demo decision cannot imply
  business approval or deterministic validation;
- one `RFQ_HANDOFF_DEMO_DECISION_RECORDED` audit event is written;
- connector command, sandbox execution, compensation plan, ChangeRequest, and outbox counts do not
  increase.

## Targeted verification

```powershell
mvn -f apps/core-api/pom.xml "-Dtest=RfqHandoffDraftQuoteServiceTest,RfqHandoffDraftQuoteControllerTest,RfqToDraftQuoteServiceTest,DraftQuoteControllerTest" test
node --test apps/web-dashboard/tests/rfq-handoffs.test.mjs
npm --prefix apps/web-dashboard run lint
npm --prefix apps/web-dashboard run typecheck
npm --prefix apps/web-dashboard run build
git diff --check
```

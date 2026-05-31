# Telegram Bot Local Demo Runbook

## Purpose

Use the local bot runtime to validate controlled Telegram-style business flows without sending real Telegram replies or touching ERP/1C.

## Prerequisites

- Core API running with a tenant id available to the dashboard.
- Dashboard configured with `NEXT_PUBLIC_CORE_API_URL` and `NEXT_PUBLIC_DEMO_TENANT_ID`.
- Telegram webhook secret configured if testing the real webhook controller.

## Dashboard Flow

1. Open `/bot-settings`.
2. Confirm the Telegram runtime status.
3. Enable only the flows you want to test.
4. Set the default handoff queue.
5. Open `/bot-conversations`.
6. Use local simulation messages such as:
   - `Need quote for 10 EA SKU-100`
   - `Is SKU-100 in stock?`
   - `What is the price for SKU-100?`
   - `Any substitute for SKU-100?`
7. Confirm conversations, handoffs, policy decisions, and response drafts.

## Webhook Flow

POST a Telegram update payload to:

`/api/v1/bot-runtime/telegram/webhook`

Example:

```json
{
  "update_id": 10001,
  "message": {
    "message_id": 501,
    "chat": { "id": "90001" },
    "text": "Need quote for 10 EA SKU-100"
  }
}
```

The response is an acknowledgement only. The bot does not make a real Telegram outbound network call in Stage 12D.

## Expected Results

- The inbound message is persisted as a channel message.
- The bot message and conversation are created or updated.
- Intent and policy audit events are recorded.
- RFQ requests create review-only RFQ/draft quote attempts when source/customer validation allows.
- Risk, ambiguity, disabled policy, stale inventory, unauthorized price, and unsupported text create handoffs.

## Safety Checks

Verify after local demo:

- no draft quote is approved automatically
- no draft order is approved or finalized
- no connector command or ERP write is created
- handoff records include source context
- audit metadata includes `externalExecution=DISABLED`

## Verification Commands

Run from `apps/core-api`:

```powershell
mvn test
mvn test '-Dtest=*Bot*Test,*QuoteReview*Test,*ChannelToQuoteWiringServiceTest,*QuoteTransactionControllerTest'
```

Run from `apps/web-dashboard`:

```powershell
npm.cmd test
npm.cmd run typecheck -- --incremental false
```

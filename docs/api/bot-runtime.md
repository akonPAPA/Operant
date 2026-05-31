# Bot Runtime API

Base paths:

- `/api/v1/bot-runtime`
- `/api/v1/bot/runtime`
- Telegram webhook: `/api/v1/bot-runtime/telegram/webhook`

All endpoints are tenant scoped through the existing tenant context/header handling.

## Settings

`GET /api/v1/bot-runtime/settings`

Returns Telegram bot runtime state:

- connection id
- channel type
- external bot ids when configured
- enabled flag
- allowed flows
- default handoff queue
- last seen timestamp
- safe response template boundaries

`POST /api/v1/bot-runtime/settings`

Body:

```json
{
  "enabled": true,
  "allowedFlows": ["GREETING", "REQUEST_QUOTE", "HUMAN_HANDOFF"],
  "defaultHandoffQueue": "BOT_REVIEW"
}
```

The backend sanitizes flows to the Stage 12D allowed registry.

## Telegram Webhook

`POST /api/v1/bot-runtime/telegram/webhook`

Receives Telegram update payloads. The controller verifies the Telegram secret token using the channel verification service, ignores unsupported update types safely, rejects malformed text payloads, and delegates normalized processing to `BotRuntimeService`.

Message idempotency is based on Telegram `update_id:message_id` and the channel gateway external message id.

## Simulation

`POST /api/v1/bot-runtime/messages/simulate`

Local operator/demo simulation that still uses backend policy and channel persistence. It does not contact Telegram.

## Conversations

`GET /api/v1/bot-runtime/conversations`

Lists tenant-owned bot conversations.

`GET /api/v1/bot-runtime/conversations/{id}`

Returns conversation header, messages, handoffs, and response drafts.

## Handoffs

`GET /api/v1/bot-runtime/handoffs`

Lists tenant-owned bot handoff queue rows with source context, detected intent, assigned queue, extracted hints, and risk flags.

`POST /api/v1/bot-runtime/conversations/{id}/handoff`

Creates a manual bot handoff for a tenant-owned conversation/message.

`POST /api/v1/bot-runtime/conversations/{id}/review-handoff`

Creates or reuses an operator review case for a bot conversation.

## Response Drafts

`POST /api/v1/bot-runtime/conversations/{id}/responses/draft`

Creates an operator-reviewed response draft. Unsupported responses are blocked by policy.

`POST /api/v1/bot-runtime/responses/{id}/mark-ready`

Marks a draft ready for local stub send after operator review.

`POST /api/v1/bot-runtime/responses/{id}/stub-send`

Records a local no-op send state. It emits audit with `externalExecution=DISABLED`; it does not call Telegram.

## Error and Policy Behavior

Policy denials return safe customer/operator-facing text and create handoff where appropriate. The API does not expose stack traces, internal margin, cross-tenant data, or unverified customer-specific prices.

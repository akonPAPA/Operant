# Stage 12D - Controlled Bot Runtime + Human Handoff

## Scope

Stage 12D adds a controlled Telegram-oriented bot runtime for bounded B2B workflows:

- persist Telegram/customer messages through the existing channel gateway
- classify supported business intents with deterministic rules
- enforce tenant bot settings, allowed flows, idempotency, and rate limits
- run safe availability, price, RFQ, substitute, status, greeting, and handoff paths
- preserve source/conversation context in bot handoffs
- route RFQ/draft quote work through Stage 12B and operator review through Stage 12C
- expose bot settings, conversations, handoffs, and audit context in the dashboard

## Non-goals

- no autonomous quote, order, discount, or substitute approval
- no direct bot-to-database trusted-state mutation outside backend services
- no direct ERP/1C production write
- no free-form tenant data search
- no no-code bot builder
- no UI-only status hiding backend policy or validation failures

## Lifecycle Flow

1. Telegram webhook verifies the configured secret/token boundary.
2. The webhook normalizes the inbound Telegram payload and quickly calls the bot runtime.
3. `ChannelGatewayService` persists a tenant-scoped `ChannelMessage` and deduplicates the external update/message id.
4. `BotRuntimeService` resolves `BotConnection`, conversation, rate limit state, and customer context.
5. `RuleBasedBotIntentClassifier` proposes an intent.
6. `BotPolicyService` and connection allowed flows decide whether the flow may proceed.
7. Safe flows either answer with bounded templates or create a handoff.
8. RFQ requests create `BotRfqRequest` and, when the Stage 12B service is available, request draft quote conversion with `actorType=BOT`, `forceReview=true`, and ERP execution disabled.
9. Risk, ambiguity, stale data, unsupported flows, disabled bot state, and unknown customer identity create `BotHandoff` records.

## Intent and Flow Model

Allowed flow names:

- `GREETING`
- `CHECK_AVAILABILITY`
- `CHECK_PRICE`
- `REQUEST_QUOTE`
- `SUGGEST_SUBSTITUTE`
- `ORDER_OR_QUOTE_STATUS`
- `HUMAN_HANDOFF`
- `UNSUPPORTED_REQUEST_SAFE_REPLY`

The deterministic classifier remains conservative. AI classification, if added later, may only suggest an intent and cannot bypass the backend policy decision.

## Handoff Model

`BotHandoff` now stores conversation and source context:

- channel message id
- resolved customer id when available
- detected intent
- assigned handoff queue
- extracted product/quantity/UOM hints
- risk flags

Quote-linked handoffs are visible through the Stage 12C quote review path when Stage 12B creates a draft quote/source link. Bot-only handoffs remain in the bot handoff queue and conversation view.

## RFQ-to-Review Path

The bot does not create final quotes. RFQ requests are captured as `BotRfqRequest` and passed to `ChannelToQuoteWiringService.createFromChannelMessage` with review forced. Resulting draft quote conversion attempts use the existing quote transaction and validation services.

## Substitute Behavior

Substitute suggestions use `ProductSubstitutionService` when available:

- blocked substitutes are never shown
- high-risk or approval-required substitutes create handoff
- displayed candidates are informational only
- quote-line substitute selection remains a Stage 12C command path

## Audit Events

Stage 12D emits:

- `BOT_MESSAGE_RECEIVED`
- `BOT_INTENT_CLASSIFIED`
- `BOT_POLICY_DENIED`
- `BOT_FLOW_STARTED`
- `BOT_AVAILABILITY_CHECKED`
- `BOT_PRICE_CHECKED`
- `BOT_RFQ_CREATED`
- `BOT_QUOTE_DRAFT_REQUESTED`
- `BOT_SUBSTITUTE_SUGGESTED`
- `BOT_HANDOFF_CREATED`
- `BOT_RESPONSE_SENT`
- `BOT_RATE_LIMITED`
- `BOT_ERROR`

Audit metadata includes bot connection, conversation, channel message, customer, intent, flow, policy/reason code, quote/handoff ids when available, `actorType=BOT` or `SYSTEM`, and `externalExecution=DISABLED`.

## Verification

Required commands:

- `mvn test`
- `mvn test '-Dtest=*Bot*Test,*QuoteReview*Test,*ChannelToQuoteWiringServiceTest,*QuoteTransactionControllerTest'`
- `npm.cmd test`
- `npm.cmd run typecheck -- --incremental false`

## Known Limitations

- Real outbound Telegram sends remain disabled; local stub-send records audit only.
- Bot processing is synchronous inside the current service call, though heavy work is avoided and webhook persistence is quick.
- Status lookup is routed to operators; customer-specific status disclosure is not automated.
- High-risk substitute approval is routed to handoff/review and not selected by the bot.
